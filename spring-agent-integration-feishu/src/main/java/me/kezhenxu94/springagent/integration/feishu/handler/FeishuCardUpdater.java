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
import me.kezhenxu94.springagent.core.knowledge.KnowledgeReference;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeScope;
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

  /**
   * What labels the result half of a call's pane. Not localized, and lowercase, because it reads as
   * one more of the {@code name: value} lines the input above it is made of rather than as the card
   * talking — the names in those come from the tool's own schema and are not translated either.
   */
  private static final String OUTPUT_PREFIX = "output: ";

  /** As much of a message as the card shows on the one line it gives it. */
  private static final int MAX_QUEUED_MESSAGE_LENGTH = 200;

  /**
   * How many earlier calls get a pane each. A card has a size of its own to stay within and the
   * whole pane is sent again on every call, so a turn that makes fifty of them cannot carry fifty
   * transcripts. The ones past this are said in a line rather than dropped in silence, and the
   * newest are the ones kept: a reader looking at a running turn is looking at what it just did.
   */
  private static final int CALLS_SHOWN = 20;

  /**
   * How much of a call's input and of what it returned the pane holds. Both can be enormous — a
   * file written whole, a command that prints a log — and neither is what the pane is for: it says
   * what the run did, and the answer above it says what came of it.
   */
  private static final int CALL_INPUT_CHARACTERS = 500;

  private static final int CALL_RESULT_CHARACTERS = 800;

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
   * Where the run says what knowledge it was given, or null on a panel that has no such element.
   */
  private final String referencesElementId;

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

  /**
   * How a message queued onto this run is marked where the person who sent it is looking, which is
   * their own message rather than the card. Null for a subagent, which is not something the user
   * replies to.
   */
  private final FeishuMessageReactions reactions;

  /** Which of them are on the card, so that each is added once and streamed into thereafter. */
  private final Set<String> added = new LinkedHashSet<>();

  /**
   * The topmost of the subagent panels this run has on the card, or null while it has none.
   *
   * <p>What an anchor search landing on {@link FeishuCardElements#SUBAGENTS} resolves to: that
   * entry is a place in the card's order and not an element, so the element the card's own inserts
   * name has to be one of the panels, and it has to be the first — anything else would put the
   * answer, or the thinking above it, in among the subagents rather than above them all.
   */
  private String firstSubagentPanelId;

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

  /**
   * What the run has said, as the model wrote it: a local image path is still a local path here,
   * since the card resolves those where it sends content rather than where it is given it.
   */
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

  /**
   * How many tool calls are outstanding. Counted rather than flagged so that the line naming one
   * comes off the card when the last call of the round returns and not when the first does: a round
   * can call several tools, and taking the line down while another call is still out would say the
   * run is idle when it is not.
   */
  private int toolCallsInFlight;

  /**
   * Every tool call the run has made, in the order it made them, which is the order the pane shows
   * them in. Kept rather than shown and forgotten: what a turn did is most of what a reader wants
   * to check an answer against, and a line that comes and goes leaves nothing to check.
   *
   * <p>A subagent keeps none of these — its calls are shown inline in its own panel.
   */
  private final List<ToolCall> toolCalls = new ArrayList<>();

  /** How many times the pane has been replaced, which is what makes each replacement its own. */
  private int toolPaneRevision;

  /**
   * Everything the model has thought, kept because closing the pane at the end of the run replaces
   * the element whole and a replacement without it would empty the pane it is closing.
   */
  private String reasoning = "";

  /**
   * Every document the run has been given, keyed by id so the same one retrieved again is the same
   * reference. Retrieval runs once per tool round, so a turn making several tool calls reports the
   * same passages repeatedly; without this the list would grow a duplicate on each of them.
   */
  private final Map<String, KnowledgeReference> references = new LinkedHashMap<>();

  /** The run the card was created for: it owns the card's elements and finishes it. */
  public static FeishuCardUpdater forRun(
      final FeishuCard card,
      final JsonMapper om,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages,
      final FeishuCardElements elements,
      final FeishuMessageReactions reactions) {
    final var updater =
        new FeishuCardUpdater(
            card,
            om,
            modelPricing,
            messages,
            FeishuCardElements.MESSAGE,
            FeishuCardElements.USAGE_BODY,
            FeishuCardElements.TODO,
            FeishuCardElements.REFERENCES,
            FeishuCardElements.QUEUED,
            elements,
            reactions,
            null,
            null,
            null,
            null);
    return updater;
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
        // Nor its references. A subagent retrieves knowledge of its own, but the panel has no
        // element for it and the run's footer speaks for the turn as a whole — a subagent's
        // sources belong in the report it writes, not in a second list beside the main one.
        null,
        // Nor is a subagent something the user replies to: what they say mid-run is queued onto the
        // run they can see, which is the one that started this.
        null,
        // A panel arrives complete, so a subagent has nothing to add to the card element by
        // element: everything it writes into was inserted with the panel itself.
        null,
        // And nothing said mid-run was addressed to it, so it has no message to mark.
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
      final String referencesElementId,
      final String queuedElementId,
      final FeishuCardElements elements,
      final FeishuMessageReactions reactions,
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
    this.referencesElementId = referencesElementId;
    this.queuedElementId = queuedElementId;
    this.elements = elements;
    this.reactions = reactions;
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
      final var models = String.join(" + ", this.models) + effort();
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

    /**
     * How hard the run asked the model to think, as a segment of its own after the models — the
     * footer's other facts are separated the same way, and it applies to every call the turn made
     * rather than to one of the models named.
     *
     * <p>Empty where no effort is configured, so the footer reads as it always did. An updater
     * writing into a subagent panel is built without card elements — a panel is rendered whole
     * rather than assembled from them — so there is nothing to read it from there either.
     */
    private String effort() {
      final var effort = elements == null ? null : elements.reasoningEffort();
      return effort == null || effort.isBlank() ? "" : " · " + effort;
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
   * Announces a tool call: in the run's tool pane on the card, and inline under what it has said so
   * far for a subagent, whose panel is the whole of what a reader sees of one while it works.
   *
   * <p>Tools that take a {@code description} — {@code Bash} asks the model for one, in active
   * voice, saying what the command does — describe the call far better than its name does, so that
   * text becomes the line and is left out of the fields below rather than said twice.
   */
  public synchronized void setToolStatus(
      String toolName, String toolInput, ToolContext toolContext) {
    toolCallsInFlight++;
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
    if (isSubagent()) {
      // A panel holds one streamed text and no pane of its own: a subagent is already the aside on
      // this card, and an aside inside an aside is a chevron nobody opens.
      sendContent(lastBaseContent + "\n" + header + quote(fields));
      return;
    }
    // In the pane a call is named by its tool and nothing else, so the trail reads as a list of
    // what was used rather than as a column of sentences of uneven length. Which is why the
    // description stays among the fields here: it is the model saying what this call was for, and
    // with the line above it no longer saying so, leaving it out would lose it.
    toolCalls.add(
        new ToolCall(
            toolName, input == null ? Strings.nullToEmpty(toolInput) : formatFields(input, false)));
    showToolCalls(true);
  }

  /**
   * Says a call has come back: what it returned goes into that call's pane, and for a subagent the
   * line comes off once every call of the round is in.
   *
   * <p>The subagent's line has nothing else to take it down: it is written under what the subagent
   * has said and would stand until the model writes its next word. On a model that thinks before it
   * writes, and on a turn whose next word is a long way off, that leaves a call that returned in a
   * moment sitting there as though the run were still waiting on it — the one thing the line is
   * there to say. The run's own pane keeps the call instead, which is what it is for.
   */
  public synchronized void clearToolStatus(
      final String toolName, final String toolInput, final String toolResult) {
    if (toolCallsInFlight > 0) {
      toolCallsInFlight--;
    }
    if (isSubagent()) {
      if (toolCallsInFlight == 0) {
        sendContent(withFailure(lastBaseContent));
      }
      return;
    }
    // The oldest call of that tool still waiting, since a round can have several of the same tool
    // out at once and they come back in whatever order they finish in.
    for (final var call : toolCalls) {
      if (call.toolName.equals(toolName) && call.result == null) {
        call.result = readable(toolResult);
        break;
      }
    }
    showToolCalls(true);
  }

  /**
   * The pane holding every call the turn has made, as the card should now have it.
   *
   * <p>Replaced whole on every call rather than written into: the pane grows a pane per call, and
   * an insert can only name an element of the card, never one nested in another. The first call
   * puts it on the card instead, since there is nothing there to replace yet — and it goes on
   * already holding that call, so no card ever shows an empty pane.
   */
  private synchronized void showToolCalls(final boolean expanded) {
    if (elements == null || toolCalls.isEmpty()) {
      return;
    }
    final var running = callStillOut();
    final var hidden = Math.max(0, toolCalls.size() - CALLS_SHOWN);
    final var shown =
        toolCalls.subList(hidden, toolCalls.size()).stream()
            .map(call -> new FeishuCardElements.ToolCall(call.toolName, call.rendered()))
            .toList();
    final var pane =
        elements.toolsPane(
            expanded,
            running != null
                // The tool the run is on, not what the model said the call was for: this title
                // names the whole trail, and a description reading as one call's sentence would
                // make a pane holding twenty of them look like it holds one.
                ? messages.get("card-tool-calls", running.toolName)
                // Nothing is out, so the title names the trail by its size instead. Going on
                // naming the last call would say the run is on a call that is over, and it is the
                // title a finished card keeps.
                : messages.get("card-tool-calls-done", toolCalls.size()),
            hidden,
            shown);
    if (added.contains(FeishuCardElements.TOOLS)) {
      // A key that changes with the pane: an idempotency key is what stops a retry landing twice,
      // and reusing one across replacements would have Feishu take the first and ignore the rest.
      card.replace(
          FeishuCardElements.TOOLS, pane, card.cardId() + ":tools:" + (++toolPaneRevision));
      return;
    }
    final var array = om.createArrayNode();
    array.add(om.readTree(pane));
    if (card.insertBefore(
        anchorOf(FeishuCardElements.TOOLS), array.toString(), card.cardId() + ":tools")) {
      added.add(FeishuCardElements.TOOLS);
    }
  }

  /**
   * The call the run is waiting on, which is the newest one still out — a round's calls come back
   * in whatever order they finish in, and the newest is the one a reader is watching for. It names
   * the pane while it is out; {@code null} once every call is back, and the pane is then named by
   * how many it holds.
   *
   * <p>Naming it is all it gets: every call sits in the pane, the one out included, so that what a
   * call returned lands with what it was given. Held above the list instead, the newest call showed
   * what it was given and never what it came back with, and a turn whose last act was a tool call
   * left that call's result off the card altogether.
   */
  private ToolCall callStillOut() {
    for (var i = toolCalls.size() - 1; i >= 0; i--) {
      if (toolCalls.get(i).result == null) {
        return toolCalls.get(i);
      }
    }
    return null;
  }

  /** Folds the pane away as the run ends, for the reason the reasoning pane is folded away. */
  private void closeToolsPane() {
    showToolCalls(false);
  }

  /**
   * What a tool returned, as text rather than as the wire carried it. A result reaches us
   * JSON-encoded, so one that is a plain string arrives quoted and escaped — its newlines written
   * as two characters, which is exactly what the card then showed: a whole log on one line. Reading
   * it back gives the lines to the reader. An object is laid out field by field, the way a call's
   * input is, so the two halves of a call read alike; anything else is already the text to show.
   */
  private String readable(final String toolResult) {
    final var text = Strings.nullToEmpty(toolResult);
    try {
      final var node = om.readTree(text);
      if (node.isString()) {
        return node.stringValue();
      }
      if (node.isObject()) {
        return formatFields(node, false);
      }
    } catch (JacksonException e) {
      // Not JSON at all, so it is whatever the tool wrote, which is what to show.
    }
    return text;
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

  /** The fields under a call's line, on the line below it, where there are any. */
  private static String quote(String text) {
    final var quoted = blockquote(text);
    return quoted.isEmpty() ? "" : "\n" + quoted;
  }

  /**
   * Text as a block quote: a call's input is something the run asked for rather than something it
   * said, which is what a quote reads as. Every line is prefixed, blank ones included, or an input
   * written in paragraphs would come out as several quotes with prose between them.
   */
  private static String blockquote(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return Arrays.stream(text.split("\n", -1))
        .map(line -> "> " + line)
        .collect(Collectors.joining("\n"));
  }

  /** As much of {@code text} as the pane gives it, saying where it was cut off. */
  private static String clipped(final String text, final int characters) {
    final var stripped = Strings.nullToEmpty(text).stripTrailing();
    return stripped.length() <= characters ? stripped : stripped.substring(0, characters) + "…";
  }

  /**
   * One tool call: the line naming it, what it was called with, and what it returned once it has.
   *
   * <p>Mutable in that one field alone, because a call is announced before it has a result and the
   * pane it sits in is rebuilt on every call after it.
   */
  private static final class ToolCall {
    private final String toolName;
    private final String input;
    private String result;

    private ToolCall(final String toolName, final String input) {
      this.toolName = Strings.nullToEmpty(toolName);
      this.input = input;
    }

    /**
     * What opening this call shows: what it was given, quoted, and under it what came back, quoted
     * the same way and labelled so the two are told apart. A call still out has the input alone,
     * which is what says it is out.
     *
     * <p>Both halves are set the same because they are the same kind of thing — the run asking and
     * the run being answered — and a quote is what that reads as. A code block instead gave the
     * output a monospace box of its own, wider and darker than everything around it, which made a
     * one-line result look like the point of the card.
     */
    private String rendered() {
      final var asked = blockquote(clipped(input, CALL_INPUT_CHARACTERS));
      if (Strings.isNullOrEmpty(result)) {
        return asked;
      }
      final var returned = blockquote(OUTPUT_PREFIX + clipped(result, CALL_RESULT_CHARACTERS));
      return asked.isEmpty() ? returned : asked + "\n\n" + returned;
    }
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
            anchorOf(elementId), elements.forInsert(elementId), card.cardId() + ":" + elementId);
    if (inserted) {
      added.add(elementId);
      // The sources go in above the spend row, so from here on they are the top of the footer, and
      // anything placed above the footer has to clear them too.
      if (FeishuCardElements.REFERENCES.equals(elementId)) {
        card.footerGrewTo(elementId);
      }
    } else {
      log.warn(
          "No {} element on card {}, so there is nowhere to write it", elementId, card.cardId());
    }
    return inserted;
  }

  /** The card this run is written to, which is the card a subagent of it gets a panel on. */
  FeishuCard card() {
    return card;
  }

  /**
   * The element {@code elementId} is inserted above, with a place in the card's order turned into
   * something on the card: {@link FeishuCardElements} knows where the subagents go and this knows
   * which panels are there.
   */
  private synchronized String anchorOf(final String elementId) {
    final var anchor = elements.anchorOf(elementId, added);
    return FeishuCardElements.SUBAGENTS.equals(anchor) ? firstSubagentPanelId : anchor;
  }

  /**
   * Where a subagent's panel goes on this card: above the tool calls, wherever those have got to,
   * and above whatever of the footer is there if the run has made no call yet.
   *
   * <p>Every panel is anchored on the same element, so the one starting now lands under the ones
   * already there and the subagents read in the order they were started.
   */
  synchronized String subagentPanelAnchor() {
    return anchorOf(FeishuCardElements.SUBAGENTS);
  }

  /**
   * Says that a subagent's panel has landed on the card, so that the card's own elements go in
   * above the subagents rather than among them from here on.
   */
  synchronized void subagentPanelAdded(final String panelElementId) {
    if (firstSubagentPanelId == null) {
      firstSubagentPanelId = panelElementId;
    }
    added.add(FeishuCardElements.SUBAGENTS);
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
    // Grey, like the conversation hint it sits beside: both are the card talking about the run
    // rather than the run talking, and the footer reads as one line when they are the same colour.
    // The subagent panels' own spend line is left alone — it is inside a panel, not in this footer.
    final var line = "<font color='grey'>" + spend.render(startedAt) + "</font>";
    if (spendOnCard(line)) {
      card.stream(spendElementId, line);
    }
  }

  /**
   * Makes sure there is somewhere to write the spend line, and returns whether it still has to be
   * written.
   *
   * <p>The card is created carrying the spend row with the stop button alone in it, so the column
   * the line goes in is not there until the run has a line — see {@link
   * FeishuCardElements#stopButtonRow()}. Growing a row means replacing it whole, and the
   * replacement is built with the line already in it, so a first report needs no write of its own.
   *
   * <p>A replacement that fails is not remembered, so the next report tries again rather than
   * leaving the card without a spend line for the rest of the run.
   */
  private synchronized boolean spendOnCard(final String line) {
    if (elements == null || added.contains(spendElementId)) {
      // A subagent's panel came with its own spend line in it.
      return true;
    }
    if (card.replaceNow(
        FeishuCardElements.USAGE, elements.usageRow(line), card.cardId() + ":" + spendElementId)) {
      added.add(spendElementId);
    }
    return false;
  }

  @Override
  public void onModel(String model) {
    updateUsageFooter(model, null);
  }

  @Override
  public void onContent(String contentSoFar) {
    updateContent(contentSoFar);
  }

  @Override
  public void onUsage(String model, Usage usage) {
    updateUsageFooter(model, usage);
  }

  @Override
  public void onKnowledgeRetrieved(List<KnowledgeReference> retrieved) {
    updateReferencesFooter(retrieved);
  }

  /**
   * Names, in the footer's knowledge-sources panel, the documents the run was given before it
   * answered.
   *
   * <p>Worth the line because retrieval is the one thing a run does that leaves no other trace: the
   * model did not ask for it, so it appears among no tool calls, and the passages reach the model
   * folded into the user's own message. Without this the reader sees an answer informed by
   * something they cannot identify, and cannot tell a well-sourced answer from a confident guess.
   *
   * <p>Cumulative over the turn, and de-duplicated by document, for the same reason the spend line
   * is a total: retrieval runs once per tool round, so the last report is not the whole of what the
   * turn read.
   */
  public synchronized void updateReferencesFooter(final List<KnowledgeReference> retrieved) {
    if (referencesElementId == null || retrieved == null || retrieved.isEmpty()) {
      return;
    }
    for (final var reference : retrieved) {
      references.merge(
          reference.docId(),
          reference,
          (existing, incoming) -> existing.score() == null ? incoming : existing);
    }
    if (references.isEmpty() || !added(referencesElementId)) {
      return;
    }
    // Into the panel's body, not the panel: the panel is what the card carries and what an insert
    // names, and this is what a write names — the same split the reasoning pane has.
    card.stream(FeishuCardElements.REFERENCES_BODY, renderReferences());
  }

  /**
   * One grey line per document: what it is called, which knowledge base it came from, and where it
   * came from originally when that says something the title does not.
   *
   * <p>The colour is opened and closed on each line rather than wrapped around the block. A font
   * tag does not survive the line breaks and list markup between its ends — the opening tag is
   * closed off by the first line and the trailing one is left with nothing to close, so it shows up
   * on the card as the literal text {@code </font>}. Every other coloured thing on this card is a
   * single line for the same reason.
   *
   * <p>Grey and notation-sized like the spend line beside it: both are the card talking about the
   * run rather than the run talking, and the footer reads as one block when they match.
   */
  private String renderReferences() {
    final var rendered = new StringBuilder();
    for (final var reference : references.values()) {
      final var title =
          Strings.isNullOrEmpty(reference.title()) ? reference.docId() : reference.title();
      final var source = reference.source();
      final var line = new StringBuilder();
      if (isLink(source)) {
        // The title becomes the link rather than the address being printed beside it: a wiki URL
        // is long, says nothing a reader wants to read, and the one useful thing about it is that
        // it can be clicked.
        line.append('[').append(title).append("](").append(source).append(')');
        line.append(" · ").append(scopeLabel(reference.scope()));
      } else {
        line.append(title).append(" · ").append(scopeLabel(reference.scope()));
        // Only when it adds something. A note stored from the conversation has no origin but
        // itself, and repeating the title would pad every line to say nothing.
        if (!Strings.isNullOrEmpty(source) && !source.equals(title)) {
          line.append(" · ").append(source);
        }
      }
      rendered.append("- <font color='grey'>").append(line).append("</font>\n");
    }
    return rendered.toString().stripTrailing();
  }

  /**
   * Whether the source is something a reader can open.
   *
   * <p>Only http and https: a file path is not reachable from the phone the card is being read on,
   * so linking one would offer a reader something that cannot work.
   */
  private static boolean isLink(final String source) {
    return source != null && (source.startsWith("http://") || source.startsWith("https://"));
  }

  private String scopeLabel(final KnowledgeScope.Target scope) {
    return messages.get(
        switch (scope) {
          case GROUP -> "reference-scope-group";
          case TENANT -> "reference-scope-tenant";
          case OWN -> "reference-scope-own";
        });
  }

  @Override
  public void onError(Throwable error) {
    showError(error);
  }

  /**
   * What the model thought, in a panel of its own above the answer.
   *
   * <p>Not folded into the answer: it is not what the run is saying, it is how the run got there,
   * and most readers want the one without the other. The panel is added the first time there is
   * thinking to put in it, so a turn on an endpoint that reports none never carries it.
   */
  @Override
  public synchronized void onReasoning(final String reasoningSoFar) {
    // A subagent writes into a panel that arrived complete, and no thinking of its own was put in
    // it: its report is what the run it serves is waiting for.
    if (elements == null || Strings.isNullOrEmpty(reasoningSoFar)) {
      return;
    }
    if (!added(FeishuCardElements.REASONING)) {
      return;
    }
    reasoning = reasoningSoFar;
    // Plain, like a subagent's report in its own panel: the panel and its smaller type already say
    // this is secondary, and quoting it on top of that only narrows it.
    card.stream(FeishuCardElements.REASONING_BODY, reasoningSoFar);
  }

  @Override
  public synchronized void onMessageQueued(final String requestId, final String message) {
    queued.add(
        Strings.isNullOrEmpty(message) ? messages.get("card-message-unshown") : oneLine(message));
    showQueued();
    // On their message as well as on the card. The card is a different message from theirs, and on
    // a phone it is often not the one on screen — so the card alone leaves the person who just
    // typed unable to tell whether anything registered.
    if (reactions != null) {
      reactions.queued(requestId);
    }
  }

  /** All of it at once, which is how the run reads it: one call empties the whole queue. */
  @Override
  public synchronized void onQueuedMessageRead(final List<String> requestIds) {
    read = queued.size();
    showQueued();
    if (reactions != null) {
      requestIds.forEach(reactions::read);
    }
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
      closeReasoningPane();
      closeToolsPane();
      countReferences();
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
            // The card rewrites the images in what it is streamed, but a panel is an element it is
            // given whole, so the one place a run's own words go into one resolves them itself.
            withFailure(card.reuploadImages(lastBaseContent)),
            spend.render(startedAt)),
        subagentId + ":end");
  }

  /**
   * Folds the thinking away as the run ends, leaving it on the card for whoever wants it.
   *
   * <p>The pane is open for the length of the run because while the model is thinking that is the
   * only thing happening and a reader watching an otherwise still card is owed it. Once there is an
   * answer above it, it is an aside behind one, and a finished card that is mostly working-out is a
   * finished card nobody reads. This is the only time it is closed — Feishu reports a panel's
   * chevron to nobody, so anything more often would be overruling a reader's own choice over and
   * over rather than once, at the moment the thing they were watching stopped.
   */
  /**
   * Puts the number of sources into the panel's title, once, as the run ends.
   *
   * <p>The count belongs in the title because that is the only part of a closed panel anyone reads:
   * it is what tells them whether the chevron is worth opening. A title cannot be streamed into, so
   * this replaces the element whole.
   *
   * <p>Once, and at the end, for the reason the reasoning pane closes itself only then: replacing
   * the element resets whether it is open, Feishu reports a reader's chevron to nobody, and
   * references accumulate over a turn — so updating the count as each one arrived would snap the
   * panel shut under anyone who had opened it, once per tool round.
   */
  private void countReferences() {
    if (elements == null || referencesElementId == null || references.isEmpty()) {
      return;
    }
    card.replace(
        referencesElementId,
        elements.referencesPanel(references.size(), renderReferences()),
        card.cardId() + ":references:end");
  }

  private void closeReasoningPane() {
    if (elements == null || !added.contains(FeishuCardElements.REASONING)) {
      return;
    }
    card.replace(
        FeishuCardElements.REASONING,
        elements.reasoningPanel(false, reasoning),
        card.cardId() + ":reasoning:end");
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
    final var markdown = items.isEmpty() ? "" : messages.get("card-todo-heading") + "\n" + items;
    log.info(
        "updateTodoList: cardId={}, itemCount={}",
        card.cardId(),
        todos != null ? todos.todos().size() : 0);
    if (added(todoElementId)) {
      card.stream(todoElementId, markdown);
    }
  }
}
