package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** The layout of a subagent's panel, which needs no tenant to check. */
class FeishuSubagentPanelTest {

  private final JsonMapper om = new JsonMapper();
  private final FeishuSubagentPanel panel = panel();

  @Test
  @DisplayName("the panel carries the ids the run streams into and the title it collapses to")
  void thePanelIsAddressable() {
    final var inserted = om.readTree(panel.forInsert("sub_a1b2c3d4", "Reading the timeline", null));

    assertThat(inserted.isArray()).as("an insert takes an array of elements").isTrue();
    final var element = inserted.get(0);
    assertThat(element.get("tag").stringValue()).isEqualTo("collapsible_panel");
    assertThat(element.get("element_id").stringValue())
        .isEqualTo(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"));
    assertThat(element.get("header").get("title").get("content").stringValue())
        .contains("Reading the timeline");
    // Collapsed, since what a subagent reports is not the answer.
    assertThat(element.get("expanded").booleanValue()).isFalse();
    final var body = element.get("elements").get(0);
    assertThat(body.get("element_id").stringValue())
        .isEqualTo(FeishuSubagentPanel.bodyElementId("sub_a1b2c3d4"));
    assertThat(body.get("content").stringValue()).isEmpty();
  }

  @Test
  @DisplayName("the replacement carries the report, and says how the subagent ended")
  void theReplacementCarriesTheReport() {
    final var element =
        om.readTree(
            panel.forUpdate(
                "sub_a1b2c3d4",
                "Reading the timeline",
                AgentOutcome.COMPLETED,
                "it starts Monday",
                "the-model · ↑10 ↓20 · 3s"));

    assertThat(element.isObject()).as("an update takes one element").isTrue();
    assertThat(element.get("elements").get(0).get("content").stringValue())
        .isEqualTo("it starts Monday");
    assertThat(element.get("elements").get(1).get("content").stringValue())
        .as("the panel says what that subagent alone spent")
        .isEqualTo("the-model · ↑10 ↓20 · 3s");
    assertThat(element.get("header").get("title").get("content").stringValue())
        .isNotEqualTo(panel.title("Reading the timeline", null));
  }

  @Test
  @DisplayName("two subagents of one run do not share an element id, which a card refuses")
  void idsAreDistinctPerSubagent() {
    assertThat(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"))
        .isNotEqualTo(FeishuSubagentPanel.panelElementId("sub_e5f6a7b8"));
    // Feishu allows 20 characters, beginning with a letter, of letters, digits and underscores.
    assertThat(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"))
        .hasSizeLessThanOrEqualTo(20)
        .matches("[A-Za-z][A-Za-z0-9_]*");
    assertThat(FeishuSubagentPanel.bodyElementId("sub_a1b2c3d4"))
        .isNotEqualTo(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"))
        .isNotEqualTo(FeishuSubagentPanel.footerElementId("sub_a1b2c3d4"));
  }

  private FeishuSubagentPanel panel() {
    final var panel =
        new FeishuSubagentPanel(
            om,
            new FeishuMessages(
                new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH)));
    panel.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    return panel;
  }
}
