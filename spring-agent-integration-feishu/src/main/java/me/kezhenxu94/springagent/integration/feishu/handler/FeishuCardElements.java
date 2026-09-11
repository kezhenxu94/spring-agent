package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every element of the reply card that comes and goes: the stop button, the run's answer, the
 * messages the user sent while it was working, the tool calls it made, its task list, and what the
 * turn has cost. What is left in {@code reply-card.json} is the card's configuration and its
 * footer, which is the frame all of these are placed in.
 *
 * <p>The spend row goes on as the card is created, because the stop button rides in it — a run is
 * stoppable from the moment it is on screen — and the rest the first time the run writes to one,
 * rather than being shipped empty in the card itself: an element the card carries is space the card
 * gives up on every reply, and most runs write no todo list and are interrupted by nobody. The
 * answer belongs here for that reason too: a streaming card already says in the chat list that it
 * is being written, so an empty markdown element would only add a blank line for as long as the
 * model takes to answer. {@link FeishuCardListener} puts the spend row on the card it creates;
 * {@link FeishuCardUpdater} adds the rest, each above the element named here, and streams into them
 * afterwards.
 *
 * <p>Nothing here talks to Feishu — JSON out — which is what makes the layout testable without a
 * tenant.
 */
@Component
@RequiredArgsConstructor
public class FeishuCardElements {

  /**
   * The button that cancels the run, for as long as there is a run to cancel: the right-hand end of
   * the spend row rather than an element of its own, and the id names the button inside that row,
   * so that taking it off the card at the end of the run leaves the spend line behind.
   */
  public static final String STOP = "stop";

  /** What the run itself is saying: the answer, and the tool calls it makes on the way there. */
  public static final String MESSAGE = "message";

  /** What the user said while the run was working. */
  static final String QUEUED = "queued";

  /** The panel holding what the model thought its way through, on an endpoint that reports it. */
  static final String REASONING = "reasoning";

  /**
   * The element inside that panel the thinking is streamed into. The panel is what the card carries
   * and what an insert names; this is what a write names.
   */
  static final String REASONING_BODY = "reasoning_body";

  /**
   * Where the panels of the subagents this run started sit, above the tool calls: a subagent is
   * work in its own right, with a brief and a report of its own, and the calls pane below it is the
   * run's own hands. A reader following the card downwards then goes from the answer, through the
   * work that was handed out, to the work this run did itself.
   *
   * <p>Not an element of the card and not in {@code card-elements.json} — the panels are built by
   * {@link FeishuSubagentPanel}, one per subagent, and there may be any number of them or none. It
   * is a place in {@link #ORDER} and nothing else, which is what lets an anchor be worked out for
   * them and, just as much, what stops the card's own elements from landing among them.
   */
  static final String SUBAGENTS = "subagents";

  /**
   * The pane holding every tool call the turn has made: the one it is on now in the title, and the
   * ones before it nested inside, a pane each.
   */
  static final String TOOLS = "tools";

  /**
   * One call inside that pane. Not an element of the card — the run builds one per call and nests
   * them in {@link #TOOLS} — so it is never inserted against the card's order and never appears in
   * {@link #ORDER}. It does carry an id, because the run addresses it: a call's pane is appended to
   * the tool pane when the call goes out and rewritten in place when it comes back, and both name
   * it. See {@link #toolCallElementId(int)}.
   */
  private static final String TOOL_CALL = "tool_call";

  /**
   * The line inside the tool pane standing for the calls the pane no longer shows one each. It has
   * an id for the same reason a call's pane does: the count on it changes as the window slides, and
   * rewriting the pane around it to say so is exactly what this design exists to avoid.
   */
  static final String TOOLS_EARLIER = "tools_earlier";

  /**
   * The id of the pane holding the {@code index}-th call the run has made, counting every call of
   * the turn rather than the ones this card shows — a card the run continued onto starts its
   * numbering where the card before it left off, so the ids stay the ones the run's own list is
   * keyed by and no two panes on one card can collide.
   */
  static String toolCallElementId(final int index) {
    return TOOL_CALL + "_" + index;
  }

  /**
   * The run's task list: a panel like the ones above it, titled with how many tasks it holds and
   * open while the run works. Replaced whole whenever the list changes, since the count is in the
   * title, so it has no body id — nothing is ever streamed into it.
   */
  static final String TODO = "todo";

  /** Where the knowledge the run was handed came from: the closed panel in the footer. */
  static final String REFERENCES = "references";

  /** The element inside that panel the sources are written into. */
  static final String REFERENCES_BODY = "references_body";

  /** What the turn has spent, and the row the stop button rides at the right-hand end of. */
  static final String USAGE = "usage";

  /** The element inside that row the spend is written into. */
  static final String USAGE_BODY = "usage_body";

  /**
   * The card's own elements from top to bottom, which is what decides where each one is added.
   *
   * <p>An insert names one element and lands immediately above it, so what an element is added
   * above depends on which of the others are there already — and which of them are there is the
   * run's to decide, not this file's. A model may think before it writes or write before it thinks,
   * call a tool before saying a word, and retrieve knowledge before any of that. So the order is
   * stated once here and the anchor derived from it, rather than written down per element as a rule
   * that holds for the arrivals someone thought of.
   *
   * <p>The spend row is last because it is the one element every card has from the moment it is
   * sent, which is what makes every other anchor answerable. The reading, top to bottom: what the
   * user added mid-run, what the model thought, what it answered, what it handed to a subagent,
   * what it did itself, what it means to do next, what it read, what it cost.
   *
   * <p>{@link #SUBAGENTS} is the one entry that is not an element of the card, so an anchor search
   * that lands on it gives back a place rather than something to insert against: the caller holding
   * the panels — {@code FeishuCardUpdater} — is what turns it into the topmost of them.
   *
   * <p>Stated here rather than in the template, because these are ids this file gives out, and an
   * anchor a deployment could rename is an insert that fails at runtime.
   */
  private static final List<String> ORDER =
      List.of(QUEUED, REASONING, MESSAGE, SUBAGENTS, TOOLS, TODO, REFERENCES, USAGE);

  /** The elements whose first nested element the run writes into, and the id it writes to. */
  private static final Map<String, String> BODY_IDS =
      Map.of(REASONING, REASONING_BODY, REFERENCES, REFERENCES_BODY, USAGE, USAGE_BODY);

  private final JsonMapper om;
  private final FeishuMessages messages;

  // A constructor argument rather than a @Value field, matching FeishuMessageCard: a field is an
  // injection point of its own, and AOT writes a plain assignment for it that cannot target a final
  // field. This stays final because lombok.config lists @Value as copyable, so the generated
  // constructor carries it to the parameter — remove that line and this silently becomes an
  // unresolved placeholder.
  @Value("${app.feishu.card-elements:classpath:/feishu/card-elements.json}")
  private final Resource cardElements;

  /**
   * How hard the model behind this run is being asked to think, for the thinking panel's title.
   *
   * <p>Core's contract rather than a provider's properties bean, and that distinction is the whole
   * of what this field has to teach. This used to be {@code OpenAiChatProperties}, which was two
   * bugs at once: on a deployment serving chat from another provider the bean does not exist, so
   * the card surface failed the entire context over a label; and every provider has this concept
   * anyway — Gemini's is a {@code thinkingLevel} enum beside a numeric budget — so even where it
   * did resolve it was the wrong setting to read. {@link ReasoningEffortInForce} is what each
   * provider translates its own spelling into, and it already accounts for a user who registered a
   * model of their own, which is why no {@code UserChatClients} is needed here any more.
   *
   * <p>Nullable, because a provider may publish none and because this class is built by hand in a
   * great many tests with nothing to say about model providers. Null means no effort is shown,
   * which is also what it means when a deployment states none.
   *
   * <p>The annotation reaches the generated constructor parameter without help, unlike
   * {@code @Value} above: JSpecify's {@code @Nullable} is {@code TYPE_USE}, so javac writes it onto
   * the parameter's type and Spring reads it there. No {@code lombok.copyableAnnotations} entry is
   * needed, and adding one would be a line that does nothing.
   */
  @Nullable private final ReasoningEffortInForce reasoningEffortInForce;

  /**
   * The element written into, for one the card carries and an insert names, or null where the two
   * are the same element. A panel is inserted whole and streamed into by its body, so a body that
   * has gone from the card is a panel that has to be put back.
   */
  String bodyOf(final String elementId) {
    return BODY_IDS.get(elementId);
  }

  /**
   * The element {@code elementId} is added above: the first element below it in {@link #ORDER} that
   * the card already has, which is what puts it in its place whatever order the run built the card
   * in.
   *
   * @param onCard the optional elements already added, which is what makes this answerable
   */
  String anchorOf(final String elementId, final Set<String> onCard) {
    final var place = ORDER.indexOf(elementId);
    if (place < 0) {
      throw new IllegalArgumentException("No place on the card for element " + elementId);
    }
    for (final var below : ORDER.subList(place + 1, ORDER.size())) {
      // The spend row is on every card from the moment it is sent, so the search always ends.
      if (USAGE.equals(below) || onCard.contains(below)) {
        return below;
      }
    }
    throw new IllegalStateException("Nothing to anchor " + elementId + " on");
  }

  /**
   * One element, as the card should carry it.
   *
   * <p>The id is set here rather than left to the template for the same reason the anchors are: it
   * is what the run streams into, and a deployment restyling the element has no say in it. Labels
   * are filled in here as well, so an element carrying one — the stop button — reads in the
   * workspace's language whoever puts it on the card.
   */
  public ObjectNode element(final String elementId) {
    return element(elementId, null);
  }

  /**
   * @param userId whose run this is, so that the thinking panel can say how hard <i>their</i> model
   *     was asked to think rather than what the deployment configured; null where that is unknown,
   *     which falls back to the deployment's own
   */
  @SneakyThrows
  public ObjectNode element(final String elementId, final String userId) {
    final var template =
        (ObjectNode)
            om.readTree(
                messages.renderCard(cardElements.getContentAsString(StandardCharsets.UTF_8)));
    final var element = (ObjectNode) template.get(elementId);
    if (element == null) {
      throw new IllegalStateException("No '" + elementId + "' element in " + cardElements);
    }
    element.put("element_id", elementId);
    // The thinking panel's title says how hard the model was asked to think, in the brackets the
    // other panels put a count in: it is the fact about the thinking a reader wants before deciding
    // whether to open it, and it belongs to the panel rather than to the spend line the run's cost
    // is on. Done here, on the element itself, so that the panel says it whether it is being put on
    // the card or replaced as the run ends — the two go through different callers.
    if (REASONING.equals(elementId)) {
      final var effort = reasoningEffort(userId);
      if (effort != null && !effort.isBlank()) {
        final var title = (ObjectNode) element.path("header").path("title");
        title.put("content", titleWithSuffix(title.path("content").asString(), effort));
      }
    }
    // The two panels and the spend row are the elements with something inside them that the run
    // writes to, so what is inside gets an id here as well, for the reason the element itself does:
    // a deployment restyling one has no say in what the run streams into.
    final var bodyId = BODY_IDS.get(elementId);
    if (bodyId != null) {
      final var body = bodyOf(element);
      if (body == null) {
        throw new IllegalStateException(
            "The '" + elementId + "' element in " + cardElements + " has nothing to write into");
      }
      body.put("element_id", bodyId);
    }
    // And the stop button, which is not an element of the card but a part of the spend row: the id
    // is how the run takes the button off the card when it ends, and it has to name the button
    // alone — an id on the row would take the spend line down with it.
    if (USAGE.equals(elementId)) {
      final var button = buttonOf(element);
      if (button == null) {
        throw new IllegalStateException(
            "No stop button in the '" + elementId + "' element in " + cardElements);
      }
      button.put("element_id", STOP);
    }
    return element;
  }

  /**
   * The spend row as the card is created: the stop button, and nothing else.
   *
   * <p>The button rides in this row because a run is stoppable from the moment it is on screen, but
   * until the run names a model there is nothing to say about what it has spent — and a column
   * holding an empty line is still a column, so the row's spacing would leave the button standing
   * off the left edge as though something were beside it. The spend column joins the row the first
   * time there is a line to put in it, which {@link FeishuCardUpdater} does by replacing the row
   * whole: a column cannot be added to a row already on the card.
   *
   * <p>The first column is the one dropped, matching where {@link #bodyOf} looks for the line — a
   * deployment moving the spend out of the first column would have the run writing into the wrong
   * one anyway.
   */
  public ObjectNode stopButtonRow() {
    final var row = element(USAGE);
    ((ArrayNode) row.path("columns")).remove(0);
    return row;
  }

  /**
   * The whole spend row with {@code spend} in it: what replaces the button-only row the card was
   * created with, the first time the turn has something to report.
   *
   * <p>The line is filled in here rather than streamed in afterwards so that the row arrives
   * already saying it, which is one call to Feishu instead of two and no card that flickers through
   * an empty column on the way.
   */
  @SneakyThrows
  public String usageRow(final String spend) {
    final var row = element(USAGE);
    bodyOf(row).put("content", spend == null ? "" : spend);
    return om.writeValueAsString(row);
  }

  /**
   * The nested element the run streams into: the first of the element's own, or of its first
   * column's where it has columns.
   *
   * <p>Two shapes because the spend row is a row and the panels are not — its text shares the line
   * with the stop button, so it lives in a column rather than directly in the element.
   */
  private static ObjectNode bodyOf(final ObjectNode element) {
    final var elements =
        element.has("columns")
            ? element.path("columns").path(0).path("elements")
            : element.path("elements");
    return elements.isArray() && !elements.isEmpty() ? (ObjectNode) elements.get(0) : null;
  }

  /**
   * The button in a row, wherever a deployment has put it: searched for by tag rather than by
   * position, so that restyling the row — swapping its columns around, say — cannot silently leave
   * the run with a button it has no way of removing.
   */
  private static ObjectNode buttonOf(final ObjectNode element) {
    for (final var column : element.path("columns")) {
      for (final var nested : column.path("elements")) {
        if ("button".equals(nested.path("tag").asString())) {
          return (ObjectNode) nested;
        }
      }
    }
    return null;
  }

  /**
   * The knowledge-sources panel as a whole element, its title carrying how many documents it holds
   * and its body carrying them.
   *
   * <p>Whole, because the count is in the title and a title is not something a stream can reach.
   *
   * <p>Stays closed: the count is what a closed panel is for, telling a reader how much is behind
   * the chevron so they can decide whether to open it.
   */
  @SneakyThrows
  public String referencesPanel(final int count, final String sources) {
    final var element = element(REFERENCES);
    final var title = (ObjectNode) element.path("header").path("title");
    title.put("content", titleWithSuffix(title.path("content").asString(), String.valueOf(count)));
    ((ObjectNode) element.path("elements").get(0)).put("content", sources == null ? "" : sources);
    return om.writeValueAsString(element);
  }

  /**
   * The task list as a whole element: how many tasks there are in the title, and the tasks
   * themselves inside it.
   *
   * <p>Whole for the reason the knowledge-sources panel is — the count is in the title and a title
   * is not something a stream can reach — and every time the list changes rather than once at the
   * end, because the list is the run saying what it means to do next and a stale count on it would
   * be worse than none. That the replacement reopens a panel a reader had folded is accepted: the
   * chevron is theirs to set again, and Feishu tells us nothing about where they left it.
   *
   * <p>Open, unlike the others, because a task list is the one thing on a working card that says
   * what is still to come; the template is what says so, and this only leaves it as it found it.
   */
  @SneakyThrows
  public String todoPanel(final int count, final String items) {
    final var element = element(TODO);
    final var title = (ObjectNode) element.path("header").path("title");
    title.put("content", titleWithSuffix(title.path("content").asString(), String.valueOf(count)));
    ((ObjectNode) element.path("elements").get(0)).put("content", items == null ? "" : items);
    return om.writeValueAsString(element);
  }

  /**
   * A panel's title with {@code suffix} bracketed on the end of it, inside whatever the template
   * wrapped the title in: how many sources or tasks it holds, or how hard the model was asked to
   * think — every panel here says what is behind its chevron in the same shape.
   *
   * <p>Appending to the rendered title rather than composing one here is what keeps the styling in
   * {@code card-elements.json}, where a deployment can change it. But appending naively would put
   * the brackets after the closing tag, so a grey title would be followed by a black count — hence
   * going in before it. A template whose title carries no such wrapper simply gets the brackets on
   * the end, which is the same thing for a title that never opened a tag.
   */
  private static String titleWithSuffix(final String title, final String suffix) {
    final var bracketed = "(" + suffix + ")";
    final var closing = title.lastIndexOf("</");
    return closing < 0
        ? title + bracketed
        : title.substring(0, closing) + bracketed + title.substring(closing);
  }

  /**
   * One tool call as the pane shows it: the id the run addresses this call's pane by, the line
   * naming the call, and what a reader sees on opening it. Rendered by {@link FeishuCardUpdater},
   * which is what knows how a call is worth reading.
   */
  public record ToolCall(String elementId, String title, String body) {}

  /**
   * The tool pane as a whole element: the run's state in the title, and every call it has made
   * nested inside as a pane of its own, oldest first.
   *
   * <p>Not what the run does on every call — appending one pane and rewriting one is, and this
   * rebuilds the lot, which closes every pane nested in it. It is for the three moments where there
   * is nothing to keep: putting the pane on a card that has none, putting it back after a write
   * found it gone, and folding it away as the run ends. Which is why {@code expanded} is passed in:
   * a replacement decides afresh whether the pane is open, so the run has to say each time what it
   * was.
   *
   * @param hidden how many calls are too old to be shown a pane each, said in one line rather than
   *     dropped in silence
   */
  @SneakyThrows
  public String toolsPane(
      final boolean expanded, final String title, final int hidden, final List<ToolCall> calls) {
    final var element = element(TOOLS);
    element.put("expanded", expanded);
    fillTitle(element, title);
    final var elements = (ArrayNode) element.path("elements");
    // The template's one line is the style for the line standing in for the calls too old to show,
    // and nothing else: every call is a pane, so with none dropped the pane holds panes alone.
    elements.remove(0);
    if (hidden > 0) {
      elements.add(om.readTree(earlierCallsLine(hidden)));
    }
    for (final var call : calls) {
      elements.add(om.readTree(toolCallPane(call)));
    }
    return om.writeValueAsString(element);
  }

  /**
   * The line standing in for the calls the pane no longer shows one each, as an element of its own:
   * what the run rewrites in place as the window slides past another call, so that the pane holding
   * it is left alone and the panes a reader opened stay open.
   */
  @SneakyThrows
  public String earlierCallsLine(final int hidden) {
    final var line = (ObjectNode) template().get(TOOLS).path("elements").get(0);
    line.put("element_id", TOOLS_EARLIER);
    line.put("content", messages.get("card-tool-calls-earlier", hidden));
    return om.writeValueAsString(line);
  }

  /**
   * One call inside the pane: a pane of its own, closed, opening onto what the call did.
   *
   * <p>Carries the id the run addresses it by, which is what lets a call be appended as it goes out
   * and rewritten — with what it returned and how long it took — as it comes back, without the pane
   * around it being touched. Born closed, and only ever born: nothing here reopens it, because
   * whether it is open is the reader's to decide and Feishu reports a chevron to nobody.
   */
  @SneakyThrows
  public String toolCallPane(final ToolCall call) {
    final var pane = (ObjectNode) template().get(TOOL_CALL);
    if (pane == null) {
      throw new IllegalStateException("No '" + TOOL_CALL + "' element in " + cardElements);
    }
    pane.put("element_id", call.elementId());
    fillTitle(pane, call.title());
    ((ObjectNode) pane.path("elements").get(0)).put("content", Strings.nullToEmpty(call.body()));
    return om.writeValueAsString(pane);
  }

  /** The template file, read afresh, so that what a caller edits is never the file's own copy. */
  @SneakyThrows
  private ObjectNode template() {
    return (ObjectNode)
        om.readTree(messages.renderCard(cardElements.getContentAsString(StandardCharsets.UTF_8)));
  }

  /**
   * Puts {@code title} into a panel header's title, where the template said it goes.
   *
   * <p>Substituted into what the template wrote rather than replacing it, so that the styling
   * around the placeholder — the grey the other panes' titles are set in — stays in the file a
   * deployment can edit.
   */
  private static void fillTitle(final ObjectNode panel, final String title) {
    final var element = (ObjectNode) panel.path("header").path("title");
    element.put(
        "content",
        element.path("content").asString().replace("{title}", Strings.nullToEmpty(title)));
  }

  /**
   * How hard the model was asked to think, or null where nothing was asked: a deployment that
   * states no effort, a user who turned the parameter off, or a provider that publishes no {@link
   * ReasoningEffortInForce}. Never read back from an answer, because it is not in one — a
   * completion reports the reasoning tokens it produced but never the effort it was asked for, so
   * the request side is the only side that knows.
   *
   * <p>Which is why it has to be asked of the side that builds the request, and of a contract
   * rather than of one provider's configuration: the deployment's property alone was right while
   * every run went through one model on one provider, and is now wrong twice over — a user may have
   * chosen their own, and the provider may not be the one whose property is being read.
   */
  private String reasoningEffort(final String userId) {
    return reasoningEffortInForce == null ? null : reasoningEffortInForce.forUser(userId);
  }

  /** One element as the JSON array the card element API takes for an insert. */
  @SneakyThrows
  public String forInsert(final String elementId) {
    return forInsert(elementId, null);
  }

  /**
   * @param userId whose run this is; see {@link #element(String, String)}
   */
  @SneakyThrows
  public String forInsert(final String elementId, final String userId) {
    final var array = om.createArrayNode();
    array.add(element(elementId, userId));
    return om.writeValueAsString(array);
  }
}
