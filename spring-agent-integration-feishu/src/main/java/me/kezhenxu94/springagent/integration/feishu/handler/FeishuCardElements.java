package me.kezhenxu94.springagent.integration.feishu.handler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parts of the reply card a run only sometimes has anything to put in: the messages the user
 * sent while it was working, its task list, and what the turn has cost.
 *
 * <p>Added to the card the first time the run writes to one, rather than shipped empty in the card
 * itself: an element the card carries is space the card gives up on every reply, and most runs
 * write no todo list and are interrupted by nobody. {@link FeishuCardUpdater} is what adds them,
 * each above the element named here, and streams into them afterwards.
 *
 * <p>Nothing here talks to Feishu — JSON out — which is what makes the layout testable without a
 * tenant.
 */
@Component
@RequiredArgsConstructor
public class FeishuCardElements {

  /** What the user said while the run was working. */
  static final String QUEUED = "queued";

  /** The run's task list. */
  static final String TODO = "todo";

  /** What the turn has spent. */
  static final String USAGE = "usage";

  /**
   * Which element of the card each one is added above, and so where it ends up: what the user added
   * mid-run reads above the answer, the task list under it, and the spend in the footer.
   *
   * <p>Decided here rather than in the template, because these are the elements {@code
   * reply-card.json} is required to keep — the run streams into them by id — and an anchor a
   * deployment could rename is an insert that fails at runtime.
   */
  private static final Map<String, String> ANCHORS =
      Map.of(
          QUEUED, "message",
          TODO, FeishuCard.FOOTER_ELEMENT_ID,
          USAGE, "guide");

  private final JsonMapper om;

  // Not final, matching FeishuSubagentPanel#subagentPanel: @Value on a field is an injection point
  // in its own right, and AOT generates a plain field assignment for it, which cannot target a
  // final field the way the JVM's reflective injection can.
  @Value("${app.feishu.card-elements:classpath:/feishu/card-elements.json}")
  Resource cardElements;

  /** The element {@code elementId} is added above. */
  String anchorOf(final String elementId) {
    final var anchor = ANCHORS.get(elementId);
    if (anchor == null) {
      throw new IllegalArgumentException("No anchor for card element " + elementId);
    }
    return anchor;
  }

  /**
   * One element as the JSON array the card element API takes for an insert.
   *
   * <p>The id is set here rather than left to the template for the same reason the anchors are: it
   * is what the run streams into, and a deployment restyling the element has no say in it.
   */
  @SneakyThrows
  public String forInsert(final String elementId) {
    final var template =
        (ObjectNode) om.readTree(cardElements.getContentAsString(StandardCharsets.UTF_8));
    final var element = (ObjectNode) template.get(elementId);
    if (element == null) {
      throw new IllegalStateException("No '" + elementId + "' element in " + cardElements);
    }
    element.put("element_id", elementId);
    final var array = om.createArrayNode();
    array.add(element);
    return om.writeValueAsString(array);
  }
}
