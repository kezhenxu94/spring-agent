package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import com.openai.models.completions.CompletionUsage;
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
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramMarkdown;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run, shown in a Telegram reply: what it is saying, what it is doing, what it has spent and
 * what the user has said to it since it began.
 *
 * <p>{@code SlackMessageUpdater}'s counterpart, and the same design: the run the reply was posted
 * for owns all of it, a subagent owns one panel in it, and the whole reply is rendered from this
 * state on every write, so a write that loses a race costs nothing. What differs is only what the
 * reply is made of — text and entity spans rather than blocks — and that the long parts are folded
 * into Telegram's expandable quotes, which is what keeps the thinking and the tool trail from
 * pushing the answer off a phone's screen.
 */
@Slf4j
public class TelegramMessageUpdater implements AgentResponseListener, TodoEventHandler {

  /** How a tool call is reached from inside a tool call, for the interceptor that announces it. */
  public static final ToolContextKey<TelegramMessageUpdater> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("telegramMessageUpdater", TelegramMessageUpdater.class);

  /** How many earlier calls get a line each — see {@code SlackMessageUpdater}. */
  private static final int TOOL_CALLS_SHOWN = 8;

  /** How much of a call's input a line holds. */
  private static final int TOOL_TEXT_LIMIT = 200;

  /** How much thinking is shown: the newest of it, since that is what is being read. */
  private static final int REASONING_SHOWN = 1500;

  /** How much of a subagent's report its panel holds. */
  private static final int SUBAGENT_SHOWN = 600;

  private final TelegramMessage message;
  private final JsonMapper om;
  private final Map<String, ModelPricing> modelPricing;
  private final TelegramMessages messages;
  private final TelegramMessageReactions reactions;
  private final TelegramQuestionForm questionForm;

  private final String subagentId;
  private final String subagentDescription;
  private final TelegramMessageUpdater parent;

  private final Instant startedAt = Instant.now();
  private final Spend spend = new Spend();

  private String content = "";
  private String reasoning = "";
  private String error;
  private boolean running = true;
  private AgentOutcome outcome;
  private final Map<String, KnowledgeReference> references = new LinkedHashMap<>();
  private TelegramQuestionForm.Pending pendingForm;
  private String todos = "";
  private final List<QueuedMessage> queued = new ArrayList<>();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private final Map<String, TelegramMessageUpdater> subagents = new LinkedHashMap<>();

  private final String runId;

  private TelegramMessageUpdater(
      final TelegramMessage message,
      final JsonMapper om,
      final Map<String, ModelPricing> modelPricing,
      final TelegramMessages messages,
      final TelegramMessageReactions reactions,
      final TelegramQuestionForm questionForm,
      final String runId,
      final String subagentId,
      final String subagentDescription,
      final TelegramMessageUpdater parent) {
    this.message = message;
    this.om = om;
    this.modelPricing = modelPricing == null ? Map.of() : modelPricing;
    this.messages = messages;
    this.reactions = reactions;
    this.questionForm = questionForm;
    this.runId = runId;
    this.subagentId = subagentId;
    this.subagentDescription = subagentDescription;
    this.parent = parent;
  }

  /** The run the reply belongs to, which owns all of it. */
  public static TelegramMessageUpdater forRun(
      final TelegramMessage message,
      final JsonMapper om,
      final Map<String, ModelPricing> modelPricing,
      final TelegramMessages messages,
      final TelegramMessageReactions reactions,
      final TelegramQuestionForm questionForm,
      final String runId) {
    return new TelegramMessageUpdater(
        message, om, modelPricing, messages, reactions, questionForm, runId, null, null, null);
  }

  /** A subagent of that run: it owns a panel in the same reply and nothing else. */
  public static TelegramMessageUpdater forSubagent(
      final TelegramMessageUpdater parent, final String subagentId, final String description) {
    final var updater =
        new TelegramMessageUpdater(
            parent.message,
            parent.om,
            parent.modelPricing,
            parent.messages,
            parent.reactions,
            parent.questionForm,
            subagentId,
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

  private TelegramMessageUpdater owner() {
    return parent == null ? this : parent;
  }

  // ---------------------------------------------------------------- listener

  @Override
  public synchronized void onModel(final String model) {
    spend.model(model);
  }

  @Override
  public void onContent(final String contentSoFar) {
    synchronized (this) {
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
      return;
    }
    synchronized (this) {
      retrieved.forEach(reference -> references.putIfAbsent(reference.docId(), reference));
    }
    owner().push(false);
  }

  @Override
  public void onMessageQueued(final String requestId, final String text) {
    if (isSubagent()) {
      return;
    }
    synchronized (this) {
      queued.add(new QueuedMessage(requestId, text));
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
          .filter(queuedMessage -> requestIds.contains(queuedMessage.requestId))
          .forEach(queuedMessage -> queuedMessage.read = true);
    }
    requestIds.forEach(reactions::read);
    owner().push(false);
  }

  @Override
  public void onError(final Throwable throwable) {
    synchronized (this) {
      // Below whatever the run had already said rather than in place of it — see the Slack
      // updater for why, and for why a subagent's panel gets the failure without its trace.
      error =
          isSubagent()
              ? messages.error(throwable == null ? null : throwable.getMessage())
              : messages.error(describe(throwable));
    }
    owner().push(false);
  }

  @Override
  public void onFinished(final AgentOutcome finished) {
    synchronized (this) {
      running = false;
      outcome = finished;
    }
    owner().push(!isSubagent());
  }

  @Override
  public synchronized void handle(final Todos list) {
    if (isSubagent()) {
      return;
    }
    final var listed =
        list == null || list.todos() == null ? List.<Todos.TodoItem>of() : list.todos();
    todos =
        listed.stream().map(TelegramMessageUpdater::formatTodo).collect(Collectors.joining("\n"));
    owner().push(false);
  }

  /**
   * Puts a question form on the reply and leaves it there until it is answered. The run ends as
   * soon as it has asked, so the stop button comes off now — see the Slack updater.
   */
  public void showQuestionForm(final String pendingId, final List<Question> questions) {
    synchronized (this) {
      pendingForm = new TelegramQuestionForm.Pending(pendingId, questions);
      running = false;
    }
    owner().pushAwaiting();
  }

  // ------------------------------------------------------------ tool calls

  public void setToolStatus(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    synchronized (this) {
      toolCalls.add(new ToolCall(toolName, descriptionOf(toolInput)));
    }
    owner().push(false);
  }

  public void clearToolStatus(
      final String toolName, final String toolInput, final String toolResult) {
    synchronized (this) {
      toolCalls.stream()
          .filter(call -> call.tool.equals(toolName) && !call.done)
          .findFirst()
          .ifPresent(call -> call.done = true);
    }
    owner().push(false);
  }

  // ---------------------------------------------------------------- writing

  private void push(final boolean finished) {
    final TelegramText text;
    final InlineKeyboardMarkup keyboard;
    synchronized (this) {
      text = render();
      keyboard = keyboard();
    }
    if (finished) {
      message.finish(text, keyboard);
      return;
    }
    message.stream(text, keyboard);
  }

  private void pushAwaiting() {
    final TelegramText text;
    final InlineKeyboardMarkup keyboard;
    synchronized (this) {
      text = render();
      keyboard = keyboard();
    }
    message.await(text, keyboard);
  }

  /** Called once the reply exists, to put the stop button on it before anything is streamed. */
  public void begin() {
    pushAwaiting();
  }

  /** The buttons: the form while one is waiting, the stop button while the run is. */
  private synchronized InlineKeyboardMarkup keyboard() {
    if (pendingForm != null) {
      return questionForm.keyboard(pendingForm.questions(), pendingForm.pendingId());
    }
    if (!running) {
      return InlineKeyboardMarkup.none();
    }
    return TelegramKeyboards.column(
        List.of(
            TelegramKeyboards.button(
                "⏹ " + messages.get("message-stop"), TelegramKeyboards.STOP + runId)));
  }

  /**
   * The reply as it should now stand, top to bottom, in the order things were said: what the user
   * added while it ran, then what it thought, then the answer, then the work behind it, then what
   * it cost.
   */
  synchronized TelegramText render() {
    final var sections = new ArrayList<TelegramText>();
    renderQueued(sections);
    renderReasoning(sections);
    final var answer = TelegramMarkdown.render(content);
    if (!answer.isBlank()) {
      sections.add(answer);
    }
    if (error != null) {
      sections.add(TelegramText.plain(error));
    }
    if (pendingForm != null) {
      sections.add(questionForm.text(pendingForm.questions()).trimmed());
    }
    renderSubagents(sections);
    renderTodos(sections);
    renderToolCalls(sections);
    renderReferences(sections);
    renderFooter(sections);

    final var out = TelegramText.builder();
    for (final var section : sections) {
      if (!out.isEmpty()) {
        out.text("\n\n");
      }
      out.append(section);
    }
    if (out.isEmpty()) {
      out.text(messages.get("message-starting"));
    }
    return out.build();
  }

  private void renderQueued(final List<TelegramText> sections) {
    if (queued.isEmpty()) {
      return;
    }
    final var lines =
        queued.stream()
            .map(
                queuedMessage ->
                    messages.get(
                        queuedMessage.read ? "message-read" : "message-queued",
                        clamp(Strings.nullToEmpty(queuedMessage.text), 200)))
            .collect(Collectors.joining("\n"));
    sections.add(TelegramText.builder().styled("italic", lines).build());
  }

  private void renderReasoning(final List<TelegramText> sections) {
    if (reasoning.isBlank()) {
      return;
    }
    sections.add(
        TelegramText.builder()
            .styled("bold", messages.get("message-reasoning"))
            .text("\n")
            .styled(
                "expandable_blockquote",
                b -> b.append(TelegramText.plain(reasoning.strip()).tail(REASONING_SHOWN)))
            .build());
  }

  private void renderSubagents(final List<TelegramText> sections) {
    for (final var subagent : subagents.values()) {
      final var panel = TelegramText.builder().styled("italic", subagent.panelTitle());
      final var said = subagent.contentSnapshot();
      if (!said.isBlank()) {
        panel
            .text("\n")
            .styled(
                "expandable_blockquote",
                b -> b.append(TelegramMarkdown.render(said).head(SUBAGENT_SHOWN)));
      }
      sections.add(panel.build());
    }
  }

  private synchronized String contentSnapshot() {
    return error == null ? content : content + "\n\n" + error;
  }

  private synchronized String panelTitle() {
    final var name =
        Strings.isNullOrEmpty(subagentDescription)
            ? messages.get("message-subagent-unnamed")
            : subagentDescription;
    final var key =
        running || outcome == null
            ? "message-subagent-started"
            : switch (outcome) {
              case COMPLETED -> "message-subagent-completed";
              case CANCELLED -> "message-subagent-cancelled";
              case FAILED -> "message-subagent-failed";
            };
    return messages.get(key, name) + " · " + spend.render(startedAt);
  }

  private void renderTodos(final List<TelegramText> sections) {
    if (todos.isBlank()) {
      return;
    }
    sections.add(
        TelegramText.builder()
            .styled("bold", messages.get("message-todo"))
            .text("\n")
            .text(todos)
            .build());
  }

  private void renderToolCalls(final List<TelegramText> sections) {
    if (toolCalls.isEmpty()) {
      return;
    }
    final var waiting = toolCalls.stream().anyMatch(call -> !call.done);
    final var title =
        waiting
            ? messages.get("message-tool-calls", newestOutstanding())
            : messages.get("message-tool-calls-done", toolCalls.size());
    final var hidden = Math.max(0, toolCalls.size() - TOOL_CALLS_SHOWN);
    final var lines = new ArrayList<String>();
    if (hidden > 0) {
      lines.add(messages.get("message-tool-calls-earlier", hidden));
    }
    toolCalls.subList(hidden, toolCalls.size()).forEach(call -> lines.add(call.render()));
    sections.add(
        TelegramText.builder()
            .styled("bold", title)
            .text("\n")
            .styled("expandable_blockquote", String.join("\n", lines))
            .build());
  }

  private String newestOutstanding() {
    for (var i = toolCalls.size() - 1; i >= 0; i--) {
      if (!toolCalls.get(i).done) {
        return toolCalls.get(i).label();
      }
    }
    return "";
  }

  private void renderReferences(final List<TelegramText> sections) {
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
    sections.add(
        TelegramText.builder()
            .styled("bold", messages.get("message-references", references.size()))
            .text("\n")
            .styled("expandable_blockquote", listed)
            .build());
  }

  private void renderFooter(final List<TelegramText> sections) {
    final var footer = spend.render(startedAt);
    if (!footer.isBlank()) {
      sections.add(TelegramText.builder().styled("italic", footer).build());
    }
  }

  // ----------------------------------------------------------------- spend

  /** What one run spent — see {@code SlackMessageUpdater.Spend}. */
  private final class Spend {
    private final Set<String> models = new LinkedHashSet<>();
    private long promptTokens;
    private long completionTokens;
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
      final var pricing = modelPricing.get(model);
      final var cost = pricing == null ? null : pricing.cost(usage, isThinkingMode(usage));
      if (cost != null) {
        costs.merge(pricing.currency().symbol(), cost, Double::sum);
      }
    }

    private String render(final Instant since) {
      final var named = String.join(" + ", models);
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

  private static String formatElapsed(final Duration elapsed) {
    final var seconds = elapsed.getSeconds();
    return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
  }

  // ------------------------------------------------------------- rendering

  private static String formatTodo(final TodoWriteTool.Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> "✅ " + Strings.nullToEmpty(item.content());
      case in_progress -> "⏳ " + Strings.nullToEmpty(item.activeForm());
      case pending -> "⚪ " + Strings.nullToEmpty(item.content());
    };
  }

  /**
   * What the model said the call was for, where it said anything — see the Slack updater for why a
   * tool's own description field wins over the one the runtime offers.
   */
  private String descriptionOf(final String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return null;
    }
    try {
      final var node = om.readTree(toolInput);
      if (!node.isObject()) {
        return null;
      }
      final var own = oneLineOf(node, DisplayDescription.NATIVE_FIELD);
      return own != null ? own : oneLineOf(node, DisplayDescription.FIELD);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String oneLineOf(final JsonNode node, final String field) {
    if (!node.has(field)) {
      return null;
    }
    final var text = node.get(field).asString();
    return Strings.isNullOrEmpty(text) ? null : clamp(text.replace('\n', ' '), TOOL_TEXT_LIMIT);
  }

  private static String clamp(final String text, final int limit) {
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  private static String describe(final Throwable throwable) {
    if (throwable == null) {
      return null;
    }
    final var message = throwable.getMessage();
    return Strings.isNullOrEmpty(message) ? throwable.getClass().getSimpleName() : message;
  }

  /** One tool call, and whether it has come back. */
  private static final class ToolCall {
    private final String tool;
    private final String description;
    private volatile boolean done;

    private ToolCall(final String tool, final String description) {
      this.tool = tool;
      this.description = description;
    }

    private String label() {
      return Strings.isNullOrEmpty(description) ? tool : tool + " — " + description;
    }

    private String render() {
      return (done ? "✅ " : "⏳ ") + label();
    }
  }

  /** A message that arrived mid-run, and whether the run has taken it in yet. */
  private static final class QueuedMessage {
    private final String requestId;
    private final String text;
    private volatile boolean read;

    private QueuedMessage(final String requestId, final String text) {
      this.requestId = requestId;
      this.text = text;
    }
  }
}
