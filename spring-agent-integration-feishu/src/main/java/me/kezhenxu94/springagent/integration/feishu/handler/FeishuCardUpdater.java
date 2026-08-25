package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.openai.models.completions.CompletionUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run, shown on a card: what it is saying, what it is doing, what it has spent and what the
 * user has said to it since it began, written into the elements this updater owns.
 *
 * <p>Which three is the only difference between the two kinds of run that use it. The run the card
 * was created for owns the card's own elements and finishes the card when it ends. A subagent of
 * that run owns the elements of the panel it was given, and ends by rewriting that panel rather
 * than the card — the work behind an answer, shown beside the answer, without a second card and a
 * second stop button for something nobody started directly. Everything in between — streaming the
 * answer, announcing tool calls, totalling the spend, showing a failure — is the same code writing
 * to different ids, which is why a subagent needs nothing of its own to be visible.
 *
 * <p>The card itself is {@link FeishuCard}, shared by every updater writing to it, and the lock
 * that orders their writes lives there.
 */
@Slf4j
public class FeishuCardUpdater implements AgentResponseListener, TodoEventHandler {
  public static final ToolContextKey<FeishuCardUpdater> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("FeishuCardUpdater", FeishuCardUpdater.class);

  private static final String DESCRIPTION_FIELD = "description";

  /** As much of a message as the card shows on the one line it gives it. */
  private static final int MAX_QUEUED_MESSAGE_LENGTH = 200;

  private final FeishuCard card;
  private final JsonMapper om;
  private final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing;
  private final FeishuMessages messages;

  /**
   * The element this run's own words go into: the card's message, or the panel's body. On the card
   * it is one of the elements added as it is first written to — see {@link FeishuCardElements} —
   * which is why every write to it goes through {@link #sendContent(String)}.
   */
  private final String contentElementId;

  /** Where this run's spend is written. */
  private final String spendElementId;

  /** The todo list's element, or null for a run whose elements have nowhere to put one. */
  private final String todoElementId;

  /**
   * Where what the user said mid-run is acknowledged, or null for a run with nowhere to say it. Its
   * own element rather than a line under the answer, which every streaming tick would overwrite —
   * and the first element of the card, so that the card reads in the order things were said: the
   * message this card replies to, which Feishu quotes above it, then what the user added while it
   * ran, then the answer.
   */
  private final String queuedElementId;

  /**
   * The card's optional elements, which this updater adds as it first has something to put in them.
   * Null for a subagent, whose panel arrives with all of its own.
   */
  private final FeishuCardElements elements;

  /** Which of them are on the card, so that each is added once and streamed into thereafter. */
  private final Set<String> added = new LinkedHashSet<>();

  /**
   * Set only for a subagent: the panel to rewrite when it ends, and what to call it there. Null on
   * the run the card belongs to, which finishes the card instead.
   */
  private final FeishuSubagentPanel panels;

  private final String subagentId;
  private final String description;

  /**
   * The brief that subagent was given, kept for the same reason the description is: the panel is
   * rewritten whole when it ends, and what was asked for has to still be at the top of it.
   */
  private final String brief;

  private final Instant startedAt = Instant.now();

  /**
   * What this run has spent, across every model call it made — and, on the run the card belongs to,
   * every subagent it started too, whose usage {@code SpringAgent} forwards to its listeners.
   */
  private final Spend spend = new Spend();

  private String lastBaseContent = "";

  /**
   * What the user said while the run was working, in the order they said it, and how many of those
   * the run has read. Kept rather than counted: the card shows the messages themselves, and a
   * reader deciding whether the run has understood them needs to see which ones it has taken in.
   */
  private final List<String> queued = new ArrayList<>();

  private int read;

  /**
   * The failure shown under the content, kept because a subagent's panel is rewritten whole when it
   * ends and would otherwise be rewritten without it — a panel titled failed, saying only what the
   * run had managed to report before it did.
   */
  private String failureNotice = "";

  /** The run the card was created for: it owns the card's elements and finishes it. */
  public static FeishuCardUpdater forRun(
      final FeishuCard card,
      final JsonMapper om,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages,
      final FeishuCardElements elements) {
    return new FeishuCardUpdater(
        card,
        om,
        modelPricing,
        messages,
        FeishuCardElements.MESSAGE,
        FeishuCardElements.USAGE,
        FeishuCardElements.TODO,
        FeishuCardElements.QUEUED,
        elements,
        null,
        null,
        null,
        null);
  }

  /**
   * A subagent of that run: it owns the elements of its panel, which has to be on the card already
   * — nothing can be streamed into an element that is not there yet.
   */
  public static FeishuCardUpdater forSubagent(
      final FeishuCard card,
      final JsonMapper om,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages,
      final FeishuSubagentPanel panels,
      final String subagentId,
      final String description,
      final String brief) {
    return new FeishuCardUpdater(
        card,
        om,
        modelPricing,
        messages,
        FeishuSubagentPanel.bodyElementId(subagentId),
        FeishuSubagentPanel.footerElementId(subagentId),
        // A panel holds a report and what it cost, and nothing else: a subagent's todo list would
        // have nowhere to go, so it is not offered one to write into.
        null,
        // Nor is a subagent something the user replies to: what they say mid-run is queued onto the
        // run they can see, which is the one that started this.
        null,
        // A panel arrives complete, so a subagent has nothing to add to the card element by
        // element: everything it writes into was inserted with the panel itself.
        null,
        panels,
        subagentId,
        description,
        brief);
  }

  private FeishuCardUpdater(
      final FeishuCard card,
      final JsonMapper om,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages,
      final String contentElementId,
      final String spendElementId,
      final String todoElementId,
      final String queuedElementId,
      final FeishuCardElements elements,
      final FeishuSubagentPanel panels,
      final String subagentId,
      final String description,
      final String brief) {
    this.card = card;
    this.om = om;
    this.modelPricing = modelPricing != null ? modelPricing : Map.of();
    this.messages = messages;
    this.contentElementId = contentElementId;
    this.spendElementId = spendElementId;
    this.todoElementId = todoElementId;
    this.queuedElementId = queuedElementId;
    this.elements = elements;
    this.panels = panels;
    this.subagentId = subagentId;
    this.description = description;
    this.brief = brief;
  }

  /** Whether this is a subagent's panel rather than the card's own run. */
  private boolean isSubagent() {
    return subagentId != null;
  }

  /**
   * What one run spent: which models answered, how many tokens they read and wrote, and roughly
   * what that cost. One of these per run, so a reader can see both the turn as a whole and, in each
   * panel, where it went.
   */
  private final class Spend {
    private final Set<String> models = new LinkedHashSet<>();
    private long promptTokens;
    private long completionTokens;

    /** Per currency, so a deployment pricing two models in two of them gets two figures. */
    private final Map<String, Double> costs = new LinkedHashMap<>();

    private void model(final String model) {
      models.add(model);
    }

    private void add(final String model, final Usage usage) {
      models.add(model);
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

    /** The one line of it: models, tokens, cost and how long, or nothing at all if nothing ran. */
    private String render(final Instant since) {
      final var models = String.join(" + ", this.models);
      if (promptTokens == 0 && completionTokens == 0) {
        return models;
      }
      final var cost =
          costs.entrySet().stream()
              .map(entry -> String.format(Locale.ROOT, "~%s%.2f", entry.getKey(), entry.getValue()))
              .collect(Collectors.joining(" + "));
      return String.format(
          "%s · ↑%d ↓%d%s · %s",
          models,
          promptTokens,
          completionTokens,
          cost.isEmpty() ? "" : " · " + cost,
          formatElapsed(Duration.between(since, Instant.now())));
    }
  }

  private static boolean isThinkingMode(Usage usage) {
    if (usage == null || !(usage.getNativeUsage() instanceof CompletionUsage openAiUsage)) {
      return false;
    }
    final var details = openAiUsage.completionTokensDetails().orElse(null);
    return details != null
        && details.reasoningTokens().isPresent()
        && details.reasoningTokens().get() > 0;
  }

  /**
   * What one model call cost, at the price configured for that model and for the mode it ran in, or
   * null where the model has no pricing configured. Returned rather than formatted, since the
   * footer shows the sum of every call the run made.
   */
  private Double approxCost(String model, Usage usage) {
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

  private static String formatElapsed(Duration elapsed) {
    final var seconds = elapsed.getSeconds();
    return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
  }

  private synchronized void updateContent(String content) {
    log.debug(
        "updateContent: cardId={}, element={}, length={}",
        card.cardId(),
        contentElementId,
        content != null ? content.length() : 0);
    this.lastBaseContent = content;
    this.failureNotice = "";
    sendContent(lastBaseContent);
  }

  /**
   * Appends the failure below whatever the run had already said, rather than replacing it: the
   * answer written before the error is usually most of one, and losing it costs the reader more
   * than the error tells them.
   *
   * <p>The base content is left untouched, so a later update — a retry that resumes streaming —
   * clears the failure instead of writing underneath it.
   *
   * <p>A subagent gets the failure without the stack trace behind it: its panel is an account of
   * work the reader did not ask to see the middle of, and the trace is in the log for whoever does.
   */
  private synchronized void showError(Throwable error) {
    final var summary = errorDisplay(error);
    log.warn(
        "showError: cardId={}, element={}, error={}", card.cardId(), contentElementId, summary);
    failureNotice =
        isSubagent()
            ? messages.error(summary)
            : messages.error(summary)
                + "\n\n```\n"
                + Throwables.getStackTraceAsString(error).stripTrailing()
                + "\n```";
    sendContent(withFailure(lastBaseContent));
  }

  /** What the element holds: what the run said, and under it the failure if there was one. */
  private String withFailure(final String content) {
    final var base = Strings.nullToEmpty(content);
    if (failureNotice.isEmpty()) {
      return base;
    }
    return base.isEmpty() ? failureNotice : base + "\n\n" + failureNotice;
  }

  private static String errorDisplay(Throwable error) {
    if (error == null) return null;
    final var msg = error.getMessage();
    return Strings.isNullOrEmpty(msg) ? error.getClass().getSimpleName() : msg;
  }

  /**
   * Announces a tool call, above the fields it was called with — on the card for the run it belongs
   * to, and in its own panel for a subagent, which is the whole of what a reader sees of one while
   * it works.
   *
   * <p>Tools that take a {@code description} — {@code Bash} asks the model for one, in active
   * voice, saying what the command does — describe the call far better than its name does, so that
   * text becomes the line and is left out of the fields below rather than said twice.
   */
  public synchronized void setToolStatus(
      String toolName, String toolInput, ToolContext toolContext) {
    final var input = parseObject(toolInput);
    final var description = input == null ? null : singleLine(input.path(DESCRIPTION_FIELD));
    log.info(
        "Tool call: cardId={}, element={}, tool={}", card.cardId(), contentElementId, toolName);
    final var header =
        description != null
            ? description
            : messages.get("card-calling-tool", Strings.nullToEmpty(toolName));
    final var fields =
        input == null ? Strings.nullToEmpty(toolInput) : formatFields(input, description != null);
    sendContent(lastBaseContent + "\n" + header + quote(fields));
  }

  /** The input parsed as a JSON object, or {@code null} if it is neither JSON nor an object. */
  private JsonNode parseObject(String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return null;
    }
    try {
      final var node = om.readTree(toolInput);
      return node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * The node as one line of text, or {@code null} if it holds no text to show. A description the
   * model wrote may span lines; the card gives a call one line, so they are folded into it.
   */
  private static String singleLine(JsonNode node) {
    if (!node.isString()) {
      return null;
    }
    final var text = node.stringValue().strip().replaceAll("\\s*\\R\\s*", " ");
    return text.isEmpty() ? null : text;
  }

  private static String formatFields(JsonNode input, boolean skipDescription) {
    return input.properties().stream()
        .filter(entry -> !skipDescription || !DESCRIPTION_FIELD.equals(entry.getKey()))
        .map(entry -> entry.getKey() + ": " + valueOf(entry.getValue()))
        .collect(Collectors.joining("\n"));
  }

  /**
   * A field holding an object or an array is shown as the JSON it is: readable enough, where {@code
   * asString()} refuses to coerce it and would cost the reader every other field with it.
   */
  private static String valueOf(JsonNode value) {
    return value.isContainer() ? value.toString() : value.asString();
  }

  private static String quote(String text) {
    if (text.isEmpty()) {
      return "";
    }
    return "\n"
        + Arrays.stream(text.split("\n"))
            .map(line -> "> " + line)
            .collect(Collectors.joining("\n"));
  }

  private synchronized void sendContent(String content) {
    if (!added(contentElementId)) {
      return;
    }
    card.stream(contentElementId, content);
  }

  /**
   * Puts one of the card's optional elements on the card, the first time this run has something to
   * write into it. Nothing can be streamed into an element the card does not have yet, so every
   * write to one goes through here first.
   *
   * <p>An insert that fails is not remembered, so the next write tries again — with the same
   * idempotency key, which is what stops a retry that only looked like a failure from leaving two
   * copies on the card. Returns whether the element is there to be written to.
   */
  private synchronized boolean added(final String elementId) {
    if (elements == null) {
      // A subagent: its panel came with every element it writes into.
      return true;
    }
    if (added.contains(elementId)) {
      return true;
    }
    final var inserted =
        card.insertBefore(
            elements.anchorOf(elementId),
            elements.forInsert(elementId),
            card.cardId() + ":" + elementId);
    if (inserted) {
      added.add(elementId);
    } else {
      log.warn(
          "No {} element on card {}, so there is nowhere to write it", elementId, card.cardId());
    }
    return inserted;
  }

  private String formatTodoItem(TodoWriteTool.Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> "☑ " + item.content();
      case in_progress -> "☒ **" + item.activeForm() + "**";
      case pending -> "☐ " + item.content();
    };
  }

  /**
   * Adds one model call to this run's running total and rewrites its footer with it.
   *
   * <p>A total, not the latest call: a turn is a loop, and every iteration of it reports its own
   * usage — as does every subagent the turn started, whose usage is forwarded to the run's
   * listeners for exactly this reason. Showing the last report alone said a five-call turn had cost
   * what its final call cost.
   *
   * <p>Assumes one usage report per model call, which is what the OpenAI streaming API does: usage
   * arrives on the last chunk of a call and nowhere else. A gateway that instead repeated a
   * cumulative total on every chunk would inflate this.
   */
  public synchronized void updateUsageFooter(String model, Usage usage) {
    if (Strings.isNullOrEmpty(model)) {
      log.debug("updateUsageFooter: skipped, model is empty for cardId={}", card.cardId());
      return;
    }
    if (usage == null) {
      // Named before it has spent anything, since this is also how the footer first says which
      // model is answering.
      spend.model(model);
    } else {
      spend.add(model, usage);
    }
    if (added(spendElementId)) {
      // Grey, like the conversation hint it sits beside: both are the card talking about the run
      // rather than the run talking, and the footer reads as one line when they are the same
      // colour. The subagent panels' own spend line is left alone — it is inside a panel, not in
      // this footer.
      card.stream(spendElementId, "<font color='grey'>" + spend.render(startedAt) + "</font>");
    }
  }

  @Override
  public void onModel(String model) {
    updateUsageFooter(model, null);
  }

  @Override
  public void onContent(String contentSoFar) {
    updateContent(card.reuploadImages(contentSoFar));
  }

  @Override
  public void onUsage(String model, Usage usage) {
    updateUsageFooter(model, usage);
  }

  @Override
  public void onError(Throwable error) {
    showError(error);
  }

  @Override
  public synchronized void onMessageQueued(final String message) {
    queued.add(
        Strings.isNullOrEmpty(message) ? messages.get("card-message-unshown") : oneLine(message));
    showQueued();
  }

  /** All of it at once, which is how the run reads it: one call empties the whole queue. */
  @Override
  public synchronized void onQueuedMessageRead() {
    read = queued.size();
    showQueued();
  }

  /**
   * What the user has added since the run began, one quoted line each, saying of every one of them
   * whether the run has taken it in yet.
   */
  private void showQueued() {
    if (queuedElementId == null || queued.isEmpty()) {
      return;
    }
    // The answer first: what the user added mid-run is placed above it, so the element it anchors
    // on has to be on the card before this one can be.
    if (!added(contentElementId) || !added(queuedElementId)) {
      return;
    }
    final var lines = new ArrayList<String>();
    for (var i = 0; i < queued.size(); i++) {
      final var key = i < read ? "card-message-read" : "card-message-queued";
      lines.add("> <font color='grey'>" + messages.get(key, queued.get(i)) + "</font>");
    }
    card.stream(queuedElementId, String.join("\n", lines));
  }

  /**
   * The message as the one line the card gives it. Folded because a message written over several
   * lines would otherwise break out of the quote it is shown in, and cut because the head of the
   * card is not where a long message belongs — it is in the chat above it, in full, as they sent
   * it.
   */
  private static String oneLine(final String message) {
    final var folded = message.strip().replaceAll("\\s*\\R\\s*", " ");
    return folded.length() <= MAX_QUEUED_MESSAGE_LENGTH
        ? folded
        : folded.substring(0, MAX_QUEUED_MESSAGE_LENGTH).stripTrailing() + "…";
  }

  /**
   * The card's run finishes the card. A subagent instead has its panel written one last time, as a
   * whole element rather than as streamed content: the title has to say how it ended, and a title
   * is not something that can be streamed into.
   */
  @Override
  public synchronized void onFinished(AgentOutcome outcome) {
    if (!isSubagent()) {
      card.finish();
      return;
    }
    card.replace(
        FeishuSubagentPanel.panelElementId(subagentId),
        panels.forUpdate(
            subagentId,
            description,
            brief,
            outcome,
            withFailure(lastBaseContent),
            spend.render(startedAt)),
        subagentId + ":end");
  }

  @Override
  public synchronized void handle(Todos todos) {
    if (todoElementId == null) {
      return;
    }
    final var items =
        todos == null || todos.todos() == null
            ? ""
            : todos.todos().stream().map(this::formatTodoItem).collect(Collectors.joining("\n"));
    // Nothing to show and nothing shown yet means there is nothing to say: a run that writes an
    // empty list — the tool is offered to every run — would otherwise put an element on the card to
    // hold it.
    if (items.isEmpty() && !added.contains(todoElementId)) {
      return;
    }
    final var markdown =
        items.isEmpty() ? "" : "---\n" + messages.get("card-todo-heading") + "\n" + items;
    log.info(
        "updateTodoList: cardId={}, itemCount={}",
        card.cardId(),
        todos != null ? todos.todos().size() : 0);
    if (added(todoElementId)) {
      card.stream(todoElementId, markdown);
    }
  }
}
