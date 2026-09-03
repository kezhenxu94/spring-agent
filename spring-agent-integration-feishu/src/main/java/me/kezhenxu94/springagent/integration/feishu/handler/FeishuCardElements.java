package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
   * them in {@link #TOOLS} — so it is never inserted by id and never carries one.
   */
  private static final String TOOL_CALL = "tool_call";

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
   * The chat options a run is sent with, for the one of them the card reports: how hard the model
   * was asked to think, shown in the thinking panel's title.
   *
   * <p>The bean the autoconfiguration binds, rather than the same property read back through a
   * placeholder of our own: a setting that belongs to another module is then named once, where it
   * is declared, instead of a second time here where a rename upstream would leave this silently
   * blank.
   */
  private final OpenAiChatProperties chatProperties;

  /**
   * Present only where users may choose a model of their own, which is where the deployment's
   * configured effort stops being the answer for every run: a user on a model of theirs, or on the
   * application's model with an effort of their own, is asked to think as hard as <i>they</i>
   * chose.
   *
   * <p>Injected into the field rather than taken on the constructor, and not final, for two reasons
   * that point the same way: the bean exists only where {@code app.ai.user-models.encryption-key}
   * is set, and this class is built by hand in a great many tests that have nothing to do with
   * which model a run went through. Null is an ordinary state, and means the deployment's own
   * setting.
   */
  @Autowired(required = false)
  UserChatClients userChatClients;

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
   * One tool call as the pane shows it: the line naming the call, and what a reader sees on opening
   * it. Rendered by {@link FeishuCardUpdater}, which is what knows how a call is worth reading.
   */
  public record ToolCall(String title, String body) {}

  /**
   * The tool pane as a whole element: the run's state in the title, and every call it has made
   * nested inside as a pane of its own, oldest first.
   *
   * <p>Whole every time, because the pane grows a pane per call and a card element insert can only
   * name an element of the card, never one inside another. Which is also why {@code expanded} is
   * passed in: a replacement decides afresh whether the pane is open, so the run has to say each
   * time what it was.
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
    final var line = (ObjectNode) elements.remove(0);
    if (hidden > 0) {
      elements.add(line.put("content", messages.get("card-tool-calls-earlier", hidden)));
    }
    for (final var call : calls) {
      elements.add(toolCallPane(call));
    }
    return om.writeValueAsString(element);
  }

  /** One call inside the pane: a pane of its own, closed, opening onto what the call did. */
  @SneakyThrows
  private ObjectNode toolCallPane(final ToolCall call) {
    final var template =
        (ObjectNode)
            om.readTree(
                    messages.renderCard(cardElements.getContentAsString(StandardCharsets.UTF_8)))
                .get(TOOL_CALL);
    if (template == null) {
      throw new IllegalStateException("No '" + TOOL_CALL + "' element in " + cardElements);
    }
    final var pane = template.deepCopy();
    fillTitle(pane, call.title());
    ((ObjectNode) pane.path("elements").get(0)).put("content", Strings.nullToEmpty(call.body()));
    return pane;
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
   * states no effort, a user who turned the parameter off, or a chat model that is not
   * OpenAI-shaped. Never read back from an answer, because it is not in one — a chat completion
   * reports the reasoning tokens it produced but never the effort it was asked for, so the request
   * side is the only side that knows.
   *
   * <p>Which is why it has to be asked of the same code that builds the request. Reading the
   * deployment's property alone was right while every run went through one model; with a user able
   * to choose, it is a label reporting somebody else's setting.
   */
  private String reasoningEffort(final String userId) {
    if (userChatClients == null || userId == null) {
      return chatProperties == null ? null : chatProperties.getReasoningEffort();
    }
    return userChatClients.effortInForce(userId);
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
