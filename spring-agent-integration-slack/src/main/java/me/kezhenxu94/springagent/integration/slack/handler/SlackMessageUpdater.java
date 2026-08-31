package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.openai.models.completions.CompletionUsage;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.element.BlockElement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ModelPricing;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeReference;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run, shown in a Slack message: what it is saying, what it is doing, what it has spent and
 * what the user has said to it since it began.
 *
 * <p>Which of those it owns is the only difference between the two kinds of run that use it. The
 * run the message was posted for owns the whole message and finishes it when it ends. A subagent of
 * that run owns one panel in it, and ends by rewriting that panel rather than the message — the
 * work behind an answer, shown beside the answer, without a second message and a second stop button
 * for something nobody started directly.
 *
 * <p><b>The whole message is rendered from this state on every write.</b> {@code chat.update}
 * replaces the message wholesale, so there is nothing to order and nothing to address: a write that
 * loses a race costs nothing, because the next one carries everything anyway. Keep it that way — a
 * partial update would need a way to name an element and a way to refuse a write that arrived out
 * of turn, and both are problems this design does not have.
 */
@Slf4j
public class SlackMessageUpdater implements AgentResponseListener, TodoEventHandler {

  /** How a tool call is reached from inside a tool call, for the interceptor that announces it. */
  public static final ToolContextKey<SlackMessageUpdater> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("slackMessageUpdater", SlackMessageUpdater.class);

  /**
   * How many earlier calls get a line each. A message has 50 blocks to stay within and the whole
   * trail is sent again on every write, so a turn that makes fifty calls cannot carry fifty
   * transcripts. The ones past this are said in a line rather than dropped in silence, and the
   * newest are the ones kept: a reader looking at a running turn is looking at what it just did.
   */
  private static final int TOOL_CALLS_SHOWN = 8;

  /**
   * How much of a call's input and of what it returned a line holds. Both can be enormous — a file
   * written whole, a command that prints a log — and neither is what the line is for: it says what
   * the run did, and the answer above it says what came of it.
   */
  private static final int TOOL_TEXT_LIMIT = 300;

  private final SlackMessage message;
  private final JsonMapper om;
  private final Map<String, ModelPricing> modelPricing;
  private final SlackMessages messages;
  private final SlackMessageReactions reactions;
  private final SlackQuestionForm questionForm;

  /** Set only for a subagent: what to call its panel when it ends. */
  private final String subagentId;

  private final String subagentDescription;

  /** The updater of the run that started this subagent, or null on the run itself. */
  private final SlackMessageUpdater parent;

  private final Instant startedAt = Instant.now();

  private final Spend spend = new Spend();

  /** What the run has said, as the model wrote it. */
  private String content = "";

  /** Everything the model has thought, kept because the panel is rewritten whole when it ends. */
  private String reasoning = "";

  /** The failure shown under the content, kept for the same reason. */
  private String error;

  /** The stop button's state: taken off once the run has ended. */
  private boolean running = true;

  private String model;

  /**
   * What the run was given before it answered, keyed by document so a repeat is not a duplicate.
   */
  private final Map<String, KnowledgeReference> references = new LinkedHashMap<>();

  /**
   * The question form currently on the message, or null when the run is not waiting on an answer.
   *
   * <p>Held here rather than written to the message by the handler that asks, because {@code
   * chat.update} replaces the message wholesale: a form written separately would be erased by the
   * next streaming write. Everything that appears on the message is rendered from this object.
   */
  private SlackQuestionForm.Pending pendingForm;

  /** The task list, rendered once and kept. */
  private String todos = "";

  /** What the user said while the run was working, and how many of those the run has read. */
  private final List<QueuedMessage> queued = new ArrayList<>();

  /** Every tool call the run has made, in the order it made them. */
  private final List<ToolCall> toolCalls = new ArrayList<>();

  /** How many calls are outstanding, so the line comes off when the last of a round returns. */
  private int outstanding;

  /** Every subagent of this run, in the order they were started. */
  private final Map<String, SlackMessageUpdater> subagents = new LinkedHashMap<>();

  /** Which message generation this updater last rendered against — see {@link #sync()}. */
  private long generation;

  /** How much of the content was already on the message this run has now left behind. */
  private int continuedFrom;

  private final String runId;

  private final String userId;

  private SlackMessageUpdater(
      final SlackMessage message,
      final JsonMapper om,
      final Map<String, ModelPricing> modelPricing,
      final SlackMessages messages,
      final SlackMessageReactions reactions,
      final SlackQuestionForm questionForm,
      final String runId,
      final String userId,
      final String subagentId,
      final String subagentDescription,
      final SlackMessageUpdater parent) {
    this.message = message;
    this.om = om;
    this.modelPricing = modelPricing == null ? Map.of() : modelPricing;
    this.messages = messages;
    this.reactions = reactions;
    this.questionForm = questionForm;
    this.runId = runId;
    this.userId = userId;
    this.subagentId = subagentId;
    this.subagentDescription = subagentDescription;
    this.parent = parent;
    this.generation = message.generation();
  }

  /** The run the message belongs to, which owns all of it. */
  public static SlackMessageUpdater forRun(
      final SlackMessage message,
      final JsonMapper om,
      final Map<String, ModelPricing> modelPricing,
      final SlackMessages messages,
      final SlackMessageReactions reactions,
      final SlackQuestionForm questionForm,
      final String runId,
      final String userId) {
    return new SlackMessageUpdater(
        message,
        om,
        modelPricing,
        messages,
        reactions,
        questionForm,
        runId,
        userId,
        null,
        null,
        null);
  }

  /** A subagent of that run: it owns a panel in the same message and nothing else. */
  public static SlackMessageUpdater forSubagent(
      final SlackMessageUpdater parent, final String subagentId, final String description) {
    final var updater =
        new SlackMessageUpdater(
            parent.message,
            parent.om,
            parent.modelPricing,
            parent.messages,
            parent.reactions,
            parent.questionForm,
            subagentId,
            parent.userId,
            subagentId,
            description,
            parent);
    synchronized (parent) {
      parent.subagents.put(subagentId, updater);
    }
    return updater;
  }

  private boolean isSubagent() {
    return subagentId != null;
  }

  /** The updater that owns the message, which is this one unless this is a subagent's. */
  private SlackMessageUpdater owner() {
    return parent == null ? this : parent;
  }

  // ---------------------------------------------------------------- listener

  @Override
  public synchronized void onModel(final String model) {
    this.model = model;
    spend.model(model);
  }

  @Override
  public void onContent(final String contentSoFar) {
    synchronized (this) {
      sync();
      content = Strings.nullToEmpty(contentSoFar);
    }
    owner().push(false);
  }

  @Override
  public void onReasoning(final String reasoningSoFar) {
    synchronized (this) {
      reasoning = Strings.nullToEmpty(reasoningSoFar);
    }
    owner().push(false);
  }

  @Override
  public void onUsage(final String model, final Usage usage) {
    synchronized (this) {
      spend.add(model, usage);
    }
    owner().push(false);
  }

  @Override
  public void onKnowledgeRetrieved(final List<KnowledgeReference> retrieved) {
    if (retrieved == null || retrieved.isEmpty() || isSubagent()) {
      // A subagent retrieves knowledge of its own, but its panel has no room for a second list and
      // the run's footer speaks for the turn as a whole — a subagent's sources belong in the report
      // it writes.
      return;
    }
    synchronized (this) {
      // Retrieval runs once per tool round, so a turn making several calls reports the same
      // passages repeatedly; keyed by document, the list does not grow a duplicate on each.
      retrieved.forEach(reference -> references.putIfAbsent(reference.docId(), reference));
    }
    owner().push(false);
  }

  @Override
  public void onMessageQueued(final String requestId, final String text) {
    if (isSubagent()) {
      // Nothing said mid-run was addressed to a subagent: what the user says is queued onto the run
      // they can see, which is the one that started this.
      return;
    }
    synchronized (this) {
      queued.add(new QueuedMessage(requestId, text, false));
    }
    reactions.queued(requestId);
    owner().push(false);
  }

  @Override
  public void onQueuedMessageRead(final List<String> requestIds) {
    if (isSubagent() || requestIds == null) {
      return;
    }
    synchronized (this) {
      queued.stream()
          .filter(message -> requestIds.contains(message.requestId()))
          .forEach(message -> message.read = true);
    }
    requestIds.forEach(reactions::read);
    owner().push(false);
  }

  @Override
  public void onError(final Throwable throwable) {
    synchronized (this) {
      // Appended below whatever the run had already said rather than replacing it: the answer
      // written before the error is usually most of one, and losing it costs the reader more than
      // the error tells them.
      //
      // A subagent gets the failure without the trace behind it: its panel is an account of work
      // the reader did not ask to see the middle of, and the trace is in the log for whoever does.
      error =
          isSubagent()
              ? messages.error(throwable == null ? null : throwable.getMessage())
              : messages.error(describe(throwable));
    }
    owner().push(false);
  }

  @Override
  public void onFinished(final AgentOutcome outcome) {
    synchronized (this) {
      running = false;
      if (isSubagent()) {
        this.outcome = outcome;
      }
    }
    if (isSubagent()) {
      // A subagent does not finish the message; it rewrites its own panel and the run carries on.
      owner().push(false);
      return;
    }
    owner().push(true);
  }

  private AgentOutcome outcome;

  @Override
  public synchronized void handle(final Todos todos) {
    if (isSubagent()) {
      // A panel holds a report and what it cost, and nothing else: a subagent's task list would
      // have nowhere to go, so it is not offered one to write into.
      return;
    }
    final var listed =
        todos == null || todos.todos() == null ? List.<Todos.TodoItem>of() : todos.todos();
    this.todos =
        listed.stream().map(SlackMessageUpdater::formatTodo).collect(Collectors.joining("\n"));
    owner().push(false);
  }

  /** Puts a question form on the message and leaves it there until it is answered. */
  public void showQuestionForm(
      final String pendingId,
      final List<org.springaicommunity.agent.tools.AskUserQuestionTool.Question> questions) {
    synchronized (this) {
      pendingForm = new SlackQuestionForm.Pending(pendingId, questions);
      // The run ends as soon as it has asked, so the button that would otherwise come off at the
      // end has to come off now: a stop button on a run that is over can only be pressed to be
      // refused.
      running = false;
    }
    // Waited on rather than streamed: nothing else is coming to carry this, because the run ends
    // here. A form that lost its write would be a question nobody can answer.
    owner().pushAwaiting();
  }

  // ------------------------------------------------------------ tool calls

  /** Announces a tool call, from the interceptor. */
  public void setToolStatus(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    synchronized (this) {
      sync();
      outstanding++;
      toolCalls.add(new ToolCall(toolName, descriptionOf(toolInput), summarize(toolInput)));
    }
    owner().push(false);
  }

  /** Says a call has come back. */
  public void clearToolStatus(
      final String toolName, final String toolInput, final String toolResult) {
    synchronized (this) {
      if (outstanding > 0) {
        outstanding--;
      }
      // The oldest call of that tool still waiting, since a round can have several of the same tool
      // out at once and they come back in whatever order they finish in.
      toolCalls.stream()
          .filter(call -> call.tool.equals(toolName) && call.result == null)
          .findFirst()
          .ifPresent(call -> call.result = summarize(readable(toolResult)));
    }
    owner().push(false);
  }

  // ---------------------------------------------------------------- writing

  /**
   * Notices that the message this updater was filling has been left behind for another.
   *
   * <p>Noticed rather than announced: a message that has rolled over cannot call back into an
   * updater whose lock is held by the thread waiting on the very write that failed. So every path
   * that changes what will be rendered passes through here first, under this updater's own lock.
   */
  private synchronized void sync() {
    final var current = message.generation();
    if (current == generation) {
      return;
    }
    generation = current;
    // The new message carries on from what the run says next rather than repeating what filled the
    // last one — copying it over would make the continuation full before a word was written.
    continuedFrom = content.length();
    toolCalls.clear();
    reasoning = "";
  }

  /** Renders the whole message and hands it to the queue. */
  private void push(final boolean finished) {
    final List<LayoutBlock> blocks;
    final String fallback;
    synchronized (this) {
      sync();
      blocks = render();
      fallback = fallbackText();
    }
    if (finished) {
      message.finish(blocks, fallback);
      return;
    }
    message.stream(blocks, fallback);
  }

  /** Renders and waits, for the writes whose caller cannot afford to lose them. */
  private void pushAwaiting() {
    final List<LayoutBlock> blocks;
    final String fallback;
    synchronized (this) {
      sync();
      blocks = render();
      fallback = fallbackText();
    }
    message.await(blocks, fallback);
  }

  /** Called once the message exists, to put the stop button on it before anything is streamed. */
  public void begin() {
    final List<LayoutBlock> blocks;
    final String fallback;
    synchronized (this) {
      blocks = render();
      fallback = fallbackText();
    }
    message.await(blocks, fallback);
  }

  /** What a phone shows, and what a client that cannot render blocks falls back to. */
  private synchronized String fallbackText() {
    final var said = content.length() > continuedFrom ? content.substring(continuedFrom) : content;
    if (!said.isBlank()) {
      return SlackBlockKit.clamp(said, 500);
    }
    return running ? messages.get("message-calling-tool", Strings.nullToEmpty(model)) : " ";
  }

  /**
   * The message as it should now stand, top to bottom.
   *
   * <p>The order is the order things were said: what the user added while it ran, then what it
   * thought, then the answer, then the work behind it, then what it cost. A reader scanning down
   * gets the answer before the machinery.
   */
  private synchronized List<LayoutBlock> render() {
    final var blocks = new ArrayList<LayoutBlock>();

    renderQueued(blocks);
    renderReasoning(blocks);

    final var said = content.length() > continuedFrom ? content.substring(continuedFrom) : content;
    if (!said.isBlank()) {
      blocks.addAll(SlackBlockKit.paragraphs(said));
    }
    if (error != null) {
      blocks.add(SlackBlockKit.markdown(error));
    }

    renderQuestionForm(blocks);
    renderSubagents(blocks);
    renderTodos(blocks);
    renderToolCalls(blocks);
    renderReferences(blocks);
    renderFooter(blocks);

    // Never over Slack's ceiling. Trimmed from the middle rather than the end, because the answer
    // is at the top and the spend is at the bottom and both are worth more than the trail between
    // them — and because a message that is refused shows nothing at all.
    return trim(blocks);
  }

  private List<LayoutBlock> trim(final List<LayoutBlock> blocks) {
    if (blocks.size() <= SlackMessage.MAX_BLOCKS) {
      return blocks;
    }
    final var keepHead = SlackMessage.MAX_BLOCKS / 2;
    final var keepTail = SlackMessage.MAX_BLOCKS - keepHead - 1;
    final var trimmed = new ArrayList<LayoutBlock>(blocks.subList(0, keepHead));
    trimmed.add(
        SlackBlockKit.context(
            messages.get("message-tool-calls-earlier", blocks.size() - keepHead - keepTail)));
    trimmed.addAll(blocks.subList(blocks.size() - keepTail, blocks.size()));
    return trimmed;
  }

  private void renderQueued(final List<LayoutBlock> blocks) {
    if (queued.isEmpty()) {
      return;
    }
    // Its own block rather than a line under the answer, which every streaming write would
    // overwrite — and at the top, so the message reads in the order things were said.
    final var lines =
        queued.stream()
            .map(
                message ->
                    messages.get(
                        message.read ? "message-read" : "message-queued",
                        SlackBlockKit.clamp(Strings.nullToEmpty(message.text), 200)))
            .collect(Collectors.joining("\n"));
    blocks.add(SlackBlockKit.context(lines));
  }

  private void renderReasoning(final List<LayoutBlock> blocks) {
    if (reasoning.isBlank()) {
      return;
    }
    blocks.add(SlackBlockKit.context(messages.get("message-reasoning")));
    blocks.add(
        SlackBlockKit.context(SlackBlockKit.clamp(reasoning, SlackBlockKit.MAX_CONTEXT_TEXT)));
  }

  private void renderQuestionForm(final List<LayoutBlock> blocks) {
    if (pendingForm == null) {
      return;
    }
    blocks.addAll(questionForm.blocks(pendingForm.questions(), pendingForm.pendingId()));
  }

  private void renderSubagents(final List<LayoutBlock> blocks) {
    if (subagents.isEmpty()) {
      return;
    }
    for (final var subagent : subagents.values()) {
      blocks.add(SlackBlockKit.context(subagent.panelTitle()));
      final var said = subagent.contentSnapshot();
      if (!said.isBlank()) {
        blocks.add(SlackBlockKit.context(SlackBlockKit.clamp(said, 600)));
      }
    }
  }

  private synchronized String contentSnapshot() {
    return error == null ? content : content + "\n" + error;
  }

  private synchronized String panelTitle() {
    final var name =
        Strings.isNullOrEmpty(subagentDescription)
            ? messages.get("message-subagent-unnamed")
            : subagentDescription;
    final var key =
        outcome == null
            ? "message-subagent-started"
            : switch (outcome) {
              case COMPLETED -> "message-subagent-completed";
              case CANCELLED -> "message-subagent-cancelled";
              case FAILED -> "message-subagent-failed";
            };
    return messages.get(key, name) + " · " + spend.render(startedAt);
  }

  private void renderTodos(final List<LayoutBlock> blocks) {
    if (todos.isBlank()) {
      return;
    }
    blocks.add(SlackBlockKit.context(messages.get("message-todo", "")));
    blocks.add(SlackBlockKit.context(SlackBlockKit.clamp(todos, SlackBlockKit.MAX_CONTEXT_TEXT)));
  }

  private void renderToolCalls(final List<LayoutBlock> blocks) {
    if (toolCalls.isEmpty()) {
      return;
    }
    final var waiting = toolCalls.stream().filter(call -> call.result == null).count();
    final var title =
        waiting > 0
            ? messages.get("message-tool-calls", newestOutstanding())
            : messages.get("message-tool-calls-done", toolCalls.size());
    blocks.add(SlackBlockKit.context(title));

    // The oldest are hidden rather than dropped in silence: what a turn did is most of what a
    // reader wants to check an answer against, and a count says how many there were.
    final var hidden = Math.max(0, toolCalls.size() - TOOL_CALLS_SHOWN);
    if (hidden > 0) {
      blocks.add(SlackBlockKit.context(messages.get("message-tool-calls-earlier", hidden)));
    }
    final var shown = toolCalls.subList(hidden, toolCalls.size());
    final var lines = shown.stream().map(ToolCall::render).collect(Collectors.joining("\n"));
    blocks.add(SlackBlockKit.context(SlackBlockKit.clamp(lines, SlackBlockKit.MAX_CONTEXT_TEXT)));
  }

  /**
   * The call the run is waiting on, which is the newest one still out — a round's calls come back
   * in whatever order they finish in, and the newest is the one a reader is watching for.
   */
  private String newestOutstanding() {
    for (var i = toolCalls.size() - 1; i >= 0; i--) {
      if (toolCalls.get(i).result == null) {
        return toolCalls.get(i).label();
      }
    }
    return "";
  }

  private void renderReferences(final List<LayoutBlock> blocks) {
    if (references.isEmpty()) {
      return;
    }
    final var listed =
        references.values().stream()
            .map(
                reference -> {
                  final var scope =
                      reference.scope() == null
                          ? ""
                          : " ("
                              + messages.get(
                                  "reference-scope-"
                                      + reference.scope().name().toLowerCase(Locale.ROOT))
                              + ")";
                  final var title =
                      Strings.isNullOrEmpty(reference.title())
                          ? reference.docId()
                          : reference.title();
                  return "• " + title + scope;
                })
            .collect(Collectors.joining("\n"));
    blocks.add(SlackBlockKit.context(messages.get("message-references", references.size())));
    blocks.add(SlackBlockKit.context(SlackBlockKit.clamp(listed, SlackBlockKit.MAX_CONTEXT_TEXT)));
  }

  private void renderFooter(final List<LayoutBlock> blocks) {
    final var footer = spend.render(startedAt);
    if (!footer.isBlank()) {
      blocks.add(SlackBlockKit.context(footer));
    }
    if (!running) {
      return;
    }
    // The stop button goes on as the message is posted — a run is stoppable as soon as it is on
    // screen — and comes off when the run ends, because a button that can only be refused is worse
    // than none.
    final List<BlockElement> elements =
        List.of(
            SlackBlockKit.button(
                SlackStopButton.ACTION_ID, messages.get("message-stop"), runId, "danger"));
    blocks.add(SlackBlockKit.actions("sa_stop_" + runId, elements));
  }

  // ----------------------------------------------------------------- spend

  /** What one run spent: which models answered, how many tokens, and roughly what that cost. */
  private final class Spend {
    private final Set<String> models = new LinkedHashSet<>();
    private long promptTokens;
    private long completionTokens;

    /** Per currency, so a deployment pricing two models in two of them gets two figures. */
    private final Map<String, Double> costs = new LinkedHashMap<>();

    private void model(final String model) {
      if (model != null) {
        models.add(model);
      }
    }

    private void add(final String model, final Usage usage) {
      model(model);
      if (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
        return;
      }
      promptTokens += usage.getPromptTokens();
      completionTokens += usage.getCompletionTokens();
      final var cost = approxCost(model, usage);
      if (cost != null) {
        costs.merge(modelPricing.get(model).currency().symbol(), cost, Double::sum);
      }
    }

    private String render(final Instant since) {
      final var named = String.join(" + ", this.models);
      if (promptTokens == 0 && completionTokens == 0) {
        return named;
      }
      final var cost =
          costs.entrySet().stream()
              .map(entry -> String.format(Locale.ROOT, "~%s%.2f", entry.getKey(), entry.getValue()))
              .collect(Collectors.joining(" + "));
      return String.format(
          "%s · ↑%d ↓%d%s · %s",
          named,
          promptTokens,
          completionTokens,
          cost.isEmpty() ? "" : " · " + cost,
          formatElapsed(Duration.between(since, Instant.now())));
    }
  }

  private static boolean isThinkingMode(final Usage usage) {
    if (usage == null || !(usage.getNativeUsage() instanceof CompletionUsage openAiUsage)) {
      return false;
    }
    final var details = openAiUsage.completionTokensDetails().orElse(null);
    return details != null
        && details.reasoningTokens().isPresent()
        && details.reasoningTokens().get() > 0;
  }

  private Double approxCost(final String model, final Usage usage) {
    final var pricing = modelPricing.get(model);
    if (pricing == null
        || usage == null
        || usage.getPromptTokens() == null
        || usage.getCompletionTokens() == null) {
      return null;
    }
    final var inputPrice =
        isThinkingMode(usage)
            ? pricing.thinkingInputPerMillion()
            : pricing.nonThinkingInputPerMillion();
    return (usage.getPromptTokens() * inputPrice
            + usage.getCompletionTokens() * pricing.outputPerMillion())
        / 1_000_000.0;
  }

  private static String formatElapsed(final Duration elapsed) {
    final var seconds = elapsed.getSeconds();
    return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
  }

  // ------------------------------------------------------------- rendering

  private static String formatTodo(final TodoWriteTool.Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> ":white_check_mark: " + Strings.nullToEmpty(item.content());
      // The active form, which is what the model wrote to describe the work as it happens — and
      // bold, because exactly one task is in progress and it is the one a reader is looking for.
      case in_progress ->
          ":hourglass_flowing_sand: *" + Strings.nullToEmpty(item.activeForm()) + "*";
      case pending -> ":white_circle: " + Strings.nullToEmpty(item.content());
    };
  }

  /**
   * What a tool returned, as text rather than as the wire carried it.
   *
   * <p>A result reaches us JSON-encoded, so one that is a plain string arrives quoted and escaped —
   * its newlines written as two characters, which is exactly what a naive render then showed: a
   * whole log on one line. Reading it back gives the lines to the reader.
   */
  private String readable(final String toolResult) {
    if (Strings.isNullOrEmpty(toolResult)) {
      return "";
    }
    try {
      final var node = om.readTree(toolResult);
      if (node.isString()) {
        return node.stringValue();
      }
      if (node.isObject()) {
        return fields(node);
      }
      return toolResult;
    } catch (Exception e) {
      // Not JSON at all, so it is whatever the tool wrote, which is what to show.
      return toolResult;
    }
  }

  private String fields(final JsonNode node) {
    final var out = new StringBuilder();
    node.propertyStream()
        .forEach(
            entry -> {
              if (!out.isEmpty()) {
                out.append("\n");
              }
              final var value = entry.getValue();
              out.append(entry.getKey())
                  .append(": ")
                  .append(value.isValueNode() ? value.asString() : value.toString());
            });
    return out.toString();
  }

  /**
   * What the model said the call was for, where it said anything. {@code Bash} asks for one in
   * active voice, and it describes a call far better than its name does — {@code Bash} twenty times
   * over is a trail that says nothing.
   */
  private String descriptionOf(final String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return null;
    }
    try {
      final var node = om.readTree(toolInput);
      if (node.isObject() && node.has("description")) {
        final var description = node.get("description").asString();
        return Strings.isNullOrEmpty(description) ? null : description.replace('\n', ' ');
      }
    } catch (Exception ignored) {
      // Not JSON; there is nothing to read a description out of and the tool's name will do.
    }
    return null;
  }

  private String summarize(final String text) {
    if (Strings.isNullOrEmpty(text)) {
      return "";
    }
    return SlackBlockKit.clamp(text.replace('\n', ' '), TOOL_TEXT_LIMIT);
  }

  private static String describe(final Throwable throwable) {
    if (throwable == null) {
      return null;
    }
    final var message = throwable.getMessage();
    return Strings.isNullOrEmpty(message) ? throwable.getClass().getSimpleName() : message;
  }

  /** One tool call: the line naming it, and what it returned once it has. */
  private static final class ToolCall {
    private final String tool;
    private final String description;
    private final String input;
    private volatile String result;

    private ToolCall(final String tool, final String description, final String input) {
      this.tool = tool;
      this.description = description;
      this.input = input;
    }

    /**
     * The tool stays first so the trail reads down the left edge as what the run used, with the
     * sentence as the part that tells two calls of it apart. Separated by a dash rather than a
     * colon, which reads as a label introducing a value — and what the call is for is not one.
     */
    private String label() {
      return Strings.isNullOrEmpty(description) ? tool : tool + " — " + description;
    }

    private String render() {
      final var mark = result == null ? ":hourglass_flowing_sand:" : ":white_check_mark:";
      return mark + " `" + label() + "`";
    }
  }

  /** A message that arrived mid-run, and whether the run has taken it in yet. */
  private static final class QueuedMessage {
    private final String requestId;
    private final String text;
    private volatile boolean read;

    private QueuedMessage(final String requestId, final String text, final boolean read) {
      this.requestId = requestId;
      this.text = text;
      this.read = read;
    }

    private String requestId() {
      return requestId;
    }
  }
}
