package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The card panel a subagent gets: a collapsed pane holding what it reported, so the work behind an
 * answer is on the card without being in the way of it.
 *
 * <p>Nothing here talks to Feishu — JSON in, JSON out — which is what makes the layout testable
 * without a tenant. {@code FeishuCardListener} is what puts one on a card, and the {@code
 * FeishuCardUpdater} of the subagent it belongs to is what streams into it.
 */
@Component
@RequiredArgsConstructor
public class FeishuSubagentPanel {

  /** Feishu allows 20 characters, beginning with a letter, of letters, digits and underscores. */
  private static final int ID_CHARACTERS = 8;

  private final JsonMapper om;
  private final FeishuMessages messages;

  // Not final, matching FeishuQuestionForm#questionForm: @Value on a field is an injection point in
  // its own right, and AOT generates a plain field assignment for it, which cannot target a final
  // field the way the JVM's reflective injection can.
  @Value("${app.feishu.subagent-panel:classpath:/feishu/subagent-panel.json}")
  Resource subagentPanel;

  /**
   * What every id in one panel is built from. Element ids are unique across a whole card and a run
   * can have several subagents, so this is derived from the subagent's own id — the tail of it,
   * since the head is the same word on every one of them.
   */
  static String prefix(final String subagentId) {
    final var cleaned = Strings.nullToEmpty(subagentId).replaceAll("[^A-Za-z0-9]", "");
    final var tail = cleaned.substring(Math.max(0, cleaned.length() - ID_CHARACTERS));
    return "s" + tail;
  }

  /** The panel itself, which is the element replaced when the subagent finishes. */
  static String panelElementId(final String subagentId) {
    return prefix(subagentId) + "_p";
  }

  /** The text inside it, which is the element the subagent's report is streamed into. */
  static String bodyElementId(final String subagentId) {
    return prefix(subagentId) + "_b";
  }

  /** The line under the report, which is what that subagent alone spent. */
  static String footerElementId(final String subagentId) {
    return prefix(subagentId) + "_f";
  }

  /** The panel as the JSON array the card element API takes for an insert. */
  @SneakyThrows
  public String forInsert(
      final String subagentId, final String description, final AgentOutcome outcome) {
    final var array = om.createArrayNode();
    array.add(element(subagentId, description, outcome, "", ""));
    return om.writeValueAsString(array);
  }

  /** The panel as the one element the card element API takes for a replacement. */
  @SneakyThrows
  public String forUpdate(
      final String subagentId,
      final String description,
      final AgentOutcome outcome,
      final String body,
      final String footer) {
    return om.writeValueAsString(element(subagentId, description, outcome, body, footer));
  }

  /**
   * The title a reader sees on the collapsed panel: what the subagent is for, and how it is getting
   * on. One message per way it can end, so the word for it is translated rather than composed.
   */
  public String title(final String description, final AgentOutcome outcome) {
    final var label =
        Strings.isNullOrEmpty(description) ? messages.get("card-subagent-unnamed") : description;
    final var key =
        outcome == null
            ? "card-subagent-started"
            : switch (outcome) {
              case COMPLETED -> "card-subagent-completed";
              case CANCELLED -> "card-subagent-cancelled";
              case FAILED -> "card-subagent-failed";
            };
    return messages.get(key, label);
  }

  private ObjectNode element(
      final String subagentId,
      final String description,
      final AgentOutcome outcome,
      final String body,
      final String footer) {
    final var panel = template();
    panel.put("element_id", panelElementId(subagentId));
    panel.withObject("header").withObject("title").put("content", title(description, outcome));
    final var elements = panel.withArray("elements");
    final var text = (ObjectNode) elements.get(0);
    text.put("element_id", bodyElementId(subagentId));
    text.put("content", Strings.nullToEmpty(body));
    final var spent = (ObjectNode) elements.get(1);
    spent.put("element_id", footerElementId(subagentId));
    spent.put("content", Strings.nullToEmpty(footer));
    return panel;
  }

  /**
   * The template, re-read per call rather than cached, as the question form's is: one small file,
   * read only when a subagent starts or ends, and editing it needs no restart.
   */
  @SneakyThrows
  private ObjectNode template() {
    final String json;
    try {
      json = subagentPanel.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read the subagent panel template", e);
    }
    return (ObjectNode) ((ObjectNode) om.readTree(json)).get("panel").deepCopy();
  }
}
