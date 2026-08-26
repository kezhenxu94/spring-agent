package me.kezhenxu94.springagent.integration.feishu.handler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every element of the reply card that comes and goes: the stop button, the run's answer, the
 * messages the user sent while it was working, its task list, and what the turn has cost. What is
 * left in {@code reply-card.json} is the card's configuration and its footer, which is the frame
 * all of these are placed in.
 *
 * <p>The stop button goes on as the card is created — a run is stoppable from the moment it is on
 * screen — and the rest the first time the run writes to one, rather than being shipped empty in
 * the card itself: an element the card carries is space the card gives up on every reply, and most
 * runs write no todo list and are interrupted by nobody. The answer belongs here for that reason
 * too: a streaming card already says in the chat list that it is being written, so an empty
 * markdown element would only add a blank line for as long as the model takes to answer. {@link
 * FeishuCardListener} puts the button on the card it creates; {@link FeishuCardUpdater} adds the
 * rest, each above the element named here, and streams into them afterwards.
 *
 * <p>Nothing here talks to Feishu — JSON out — which is what makes the layout testable without a
 * tenant.
 */
@Component
@RequiredArgsConstructor
public class FeishuCardElements {

  /** The button that cancels the run, for as long as there is a run to cancel. */
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

  /** The run's task list. */
  static final String TODO = "todo";

  /** What the turn has spent. */
  static final String USAGE = "usage";

  /**
   * Which element of the card each one is added above, and so where it ends up: the stop button
   * just above the footer, the answer above the button, what the user added mid-run above the
   * answer, the task list under the answer, and the spend in the footer.
   *
   * <p>Decided here rather than in the template, because the two the chain hangs from are the
   * elements {@code reply-card.json} is required to keep — the footer divider and the hint below it
   * — and an anchor a deployment could rename is an insert that fails at runtime. The rest anchor
   * on each other, so each has to be on the card before whatever goes above it can be: the answer
   * needs the button, which the card is created with, and {@link #QUEUED} needs the answer, which
   * {@link FeishuCardUpdater} sees to.
   */
  private static final Map<String, String> ANCHORS =
      Map.of(
          STOP,
          FeishuCard.FOOTER_ELEMENT_ID,
          MESSAGE,
          STOP,
          QUEUED,
          MESSAGE,
          REASONING,
          MESSAGE,
          TODO,
          FeishuCard.FOOTER_ELEMENT_ID,
          USAGE,
          "guide");

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
   * was asked to think, shown beside the model in the usage footer.
   *
   * <p>The bean the autoconfiguration binds, rather than the same property read back through a
   * placeholder of our own: a setting that belongs to another module is then named once, where it
   * is declared, instead of a second time here where a rename upstream would leave this silently
   * blank.
   */
  private final OpenAiChatProperties chatProperties;

  /** The element {@code elementId} is added above, on a card carrying nothing else optional. */
  String anchorOf(final String elementId) {
    final var anchor = ANCHORS.get(elementId);
    if (anchor == null) {
      throw new IllegalArgumentException("No anchor for card element " + elementId);
    }
    return anchor;
  }

  /**
   * The element {@code elementId} is added above, given the ones the card already carries.
   *
   * <p>Two of them move, because three elements want the same stretch of card — what the user added
   * mid-run at the very top, then what the model thought, then what it answered — and which arrives
   * first is the run's to decide, not this file's. An insert names one element and lands
   * immediately above it, so an anchor fixed to {@link #MESSAGE} would put whichever of the other
   * two came second below the one that came first, which is the wrong way round half the time.
   *
   * <p>So the reasoning panel anchors on the answer once there is one and on the stop button until
   * then — the button is the one element every card has from the moment it is sent, and the answer
   * goes above it too, so a panel anchored there stays above the answer when it arrives. And the
   * queued messages anchor on the panel once there is one, which is what keeps them at the top.
   *
   * <p>The task list moves for the same reason at the other end of the card: the footer grows
   * upwards as the spend line joins the hint, and a list anchored on the hint alone would come to
   * rest under the spend rather than above it.
   *
   * @param onCard the optional elements already added, which is what makes this answerable
   */
  String anchorOf(final String elementId, final Set<String> onCard) {
    if (REASONING.equals(elementId) && !onCard.contains(MESSAGE)) {
      return STOP;
    }
    if (QUEUED.equals(elementId) && onCard.contains(REASONING)) {
      return REASONING;
    }
    if (TODO.equals(elementId) && onCard.contains(USAGE)) {
      return USAGE;
    }
    return anchorOf(elementId);
  }

  /**
   * One element, as the card should carry it.
   *
   * <p>The id is set here rather than left to the template for the same reason the anchors are: it
   * is what the run streams into, and a deployment restyling the element has no say in it. Labels
   * are filled in here as well, so an element carrying one — the stop button — reads in the
   * workspace's language whoever puts it on the card.
   */
  @SneakyThrows
  public ObjectNode element(final String elementId) {
    final var template =
        (ObjectNode)
            om.readTree(
                messages.renderCard(cardElements.getContentAsString(StandardCharsets.UTF_8)));
    final var element = (ObjectNode) template.get(elementId);
    if (element == null) {
      throw new IllegalStateException("No '" + elementId + "' element in " + cardElements);
    }
    element.put("element_id", elementId);
    // The reasoning panel is the one element with something inside it that the run writes to, so
    // what is inside gets an id here as well, for the reason the panel does: a deployment
    // restyling the panel has no say in what the run streams into.
    if (REASONING.equals(elementId)) {
      final var body = element.path("elements");
      if (!body.isArray() || body.isEmpty()) {
        throw new IllegalStateException(
            "The '" + REASONING + "' element in " + cardElements + " has nothing to write into");
      }
      ((ObjectNode) body.get(0)).put("element_id", REASONING_BODY);
    }
    return element;
  }

  /**
   * The reasoning pane as a whole element, open or closed, holding {@code reasoning}.
   *
   * <p>Whole, because whether a panel is open is not something a stream can express — the run
   * replaces the element, and a replacement that left out the thinking would take it off the card
   * along with the chevron that was the point of the replacement.
   */
  @SneakyThrows
  public String reasoningPanel(final boolean expanded, final String reasoning) {
    final var element = element(REASONING);
    element.put("expanded", expanded);
    ((ObjectNode) element.path("elements").get(0))
        .put("content", reasoning == null ? "" : reasoning);
    return om.writeValueAsString(element);
  }

  /**
   * How hard the model was asked to think, or null where nothing was asked: a deployment that
   * states no effort, or a chat model that is not OpenAI-shaped. Never read back from an answer,
   * because it is not in one — a chat completion reports the reasoning tokens it produced but never
   * the effort it was asked for, so the request side is the only side that knows.
   */
  public String reasoningEffort() {
    return chatProperties == null ? null : chatProperties.getReasoningEffort();
  }

  /** One element as the JSON array the card element API takes for an insert. */
  @SneakyThrows
  public String forInsert(final String elementId) {
    final var array = om.createArrayNode();
    array.add(element(elementId));
    return om.writeValueAsString(array);
  }
}
