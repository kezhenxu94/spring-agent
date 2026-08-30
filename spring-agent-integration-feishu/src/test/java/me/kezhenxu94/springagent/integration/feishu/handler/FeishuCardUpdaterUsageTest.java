package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ModelPricing;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ModelPricing.Currency;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The footer is what a reader judges a turn by, so it has to be the turn: every iteration of the
 * tool-calling loop, and every subagent the run started, whose usage reaches this listener too.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterUsageTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuCard card;
  private FeishuCardUpdater updater;

  private final FeishuMessages messages =
      new FeishuMessages(
          new FeishuProperties(
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    // The elements the run writes to that the card does not ship with are inserted on first use.
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    // And the spend row is replaced whole the first time there is a line to put in it, since that
    // is when the column holding it joins the row.
    final var updated = new UpdateCardElementResp();
    updated.setCode(0);
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenReturn(updated);
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    updater =
        FeishuCardUpdater.forRun(
            card,
            new JsonMapper(),
            // A tenth of a cent per thousand tokens each way, so the arithmetic is checkable.
            Map.of("the-model", new ModelPricing(1.0, 1.0, 1.0, Currency.USD)),
            messages,
            cardElements(messages),
            null);
  }

  /** The real builder: what a panel holds is the point of the assertions below. */
  private FeishuSubagentPanel panels() {
    final var panels = new FeishuSubagentPanel(new JsonMapper(), messages);
    panels.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    return panels;
  }

  @Test
  @DisplayName("the footer names the model before anything has been spent on it")
  void theModelIsNamedFirst() throws Exception {
    updater.onModel("the-model");

    // Grey, like the conversation hint below it, and so asserted whole here rather than by what it
    // says: this is the one case where the footer's whole content is known.
    assertThat(lastFooter()).isEqualTo("<font color='grey'>the-model</font>");
  }

  @Test
  @DisplayName("the spend column joins the row the first time there is a line for it")
  void theRowGrowsItsSpendColumnOnTheFirstReport() throws Exception {
    updater.onModel("the-model");

    // The card is created with the button alone in the row, so the first report is the whole row
    // again with the column in it — and the line already written, rather than an empty column the
    // card shows for as long as a second call takes to land.
    final var row = lastSpendRow();
    final var spend = spendColumnOf(row);
    assertThat(spend.path("element_id").asString()).isEqualTo("usage_body");
    assertThat(spend.path("content").asString()).isEqualTo("<font color='grey'>the-model</font>");
    // The button comes back with it, keeping the id the run ends by deleting.
    final var button = row.path("columns").path(1).path("elements").path(0);
    assertThat(button.path("tag").asString()).isEqualTo("button");
    assertThat(button.path("element_id").asString()).isEqualTo("stop");
    // And once the column is there, the reports after it are streamed rather than replacing it.
    updater.onUsage("the-model", new DefaultUsage(1_000, 2_000, 3_000));
    assertThat(lastContentOf("usage_body")).contains("↑1000 ↓2000");
  }

  @Test
  @DisplayName("the effort is not on the spend line — it belongs to the thinking panel's title")
  void theEffortIsNotOnTheSpendLine() throws Exception {
    updater =
        FeishuCardUpdater.forRun(
            card,
            new JsonMapper(),
            Map.of(),
            messages,
            new FeishuCardElements(
                new JsonMapper(),
                messages,
                new ClassPathResource("feishu/card-elements.json"),
                chatPropertiesWithEffort("xhigh")),
            null);

    updater.onModel("the-model");
    updater.onUsage("another-model", new DefaultUsage(1_000, 2_000, 3_000));

    // The line names what answered and what that cost, and nothing about how it was asked to
    // think: that is a fact about the thinking, and it is said on the panel holding the thinking
    // — see FeishuCardUpdaterReasoningTest. A footer that said it too would say it twice.
    assertThat(lastFooter())
        .contains("the-model + another-model · ↑1000 ↓2000")
        .doesNotContain("xhigh");
  }

  @Test
  @DisplayName("a run that has spent nothing yet names the model alone")
  void onlyTheModelBeforeItSpends() throws Exception {
    updater.onModel("the-model");

    assertThat(lastFooter()).isEqualTo("<font color='grey'>the-model</font>");
  }

  @Test
  @DisplayName("every model call the turn makes is added up, not just the last one")
  void everyCallIsCountedTowardsTheTurn() throws Exception {
    updater.onUsage("the-model", new DefaultUsage(1_000, 2_000, 3_000));
    updater.onUsage("the-model", new DefaultUsage(500, 500, 1_000));

    // 1500 up, 2500 down, and a cost of both together at a dollar per million.
    assertThat(lastFooter()).contains("↑1500 ↓2500").contains("~$0.00");
  }

  @Test
  @DisplayName("what a subagent spent is on the turn that asked for it, under its own model")
  void subagentUsageJoinsTheTotal() throws Exception {
    updater.onUsage("the-model", new DefaultUsage(1_000_000, 1_000_000, 2_000_000));
    // Forwarded by SpringAgent from the run this one started, and possibly on another model.
    updater.onUsage("another-model", new DefaultUsage(2_000_000, 0, 2_000_000));

    final var footer = lastFooter();
    assertThat(footer).contains("the-model + another-model").contains("↑3000000 ↓1000000");
    // Only the priced model is costed; the other is left out rather than guessed at.
    assertThat(footer).contains("~$2.00");
  }

  @Test
  @DisplayName("a subagent panel counts that subagent alone, beside the turn's own total")
  void eachSubagentPanelCountsItself() throws Exception {
    final var subagent = subagentUpdater();

    updater.onUsage("the-model", new DefaultUsage(1_000_000, 0, 1_000_000));
    // The subagent's own run reports to its own updater, and SpringAgent forwards the same tokens
    // to the listeners of the run that started it — which is where the turn's total comes from.
    subagent.onUsage("the-model", new DefaultUsage(3_000_000, 0, 0));
    updater.onUsage("the-model", new DefaultUsage(3_000_000, 0, 0));

    // The panel is that subagent's spend, and only that.
    assertThat(lastContentOf(FeishuSubagentPanel.footerElementId("sub_1"))).contains("↑3000000 ↓0");
    // The card's footer is the turn: the run's own call and the subagent's.
    assertThat(lastFooter()).contains("↑4000000 ↓0");
  }

  @Test
  @DisplayName("a subagent writes into its own panel, and never onto the card's own answer")
  void aSubagentIsConfinedToItsPanel() throws Exception {
    final var subagent = subagentUpdater();

    updater.onContent("what the run is saying");
    subagent.onContent("what the subagent found");
    subagent.setToolStatus("Bash", "{\"description\":\"Reading the log\"}", null);

    assertThat(lastContentOf("message")).isEqualTo("what the run is saying");
    assertThat(lastContentOf(FeishuSubagentPanel.bodyElementId("sub_1")))
        .isEqualTo("what the subagent found\nReading the log");
  }

  /** A subagent of the run above, panel and all, as {@code FeishuCardListener} attaches one. */
  private FeishuCardUpdater subagentUpdater() throws Exception {
    final var insert = new CreateCardElementResp();
    insert.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(insert);
    final var panels = panels();
    card.insertBeforeFooter(
        panels.forInsert(
            "sub_1", "Reading the timeline", "Read the timeline and say when it starts", null),
        "sub_1");
    return FeishuCardUpdater.forSubagent(
        card,
        new JsonMapper(),
        Map.of("the-model", new ModelPricing(1.0, 1.0, 1.0, Currency.USD)),
        messages,
        panels,
        "sub_1",
        "Reading the timeline",
        "Read the timeline and say when it starts");
  }

  /**
   * The spend line as the card last had it, wherever it was written: the first report arrives as
   * the row itself, because that is when the column holding the line joins it, and every report
   * after that is streamed into the column.
   */
  private String lastFooter() throws Exception {
    final var streamed = new ArrayList<String>();
    final var contents = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeast(0)).content(contents.capture());
    contents.getAllValues().stream()
        .filter(request -> "usage_body".equals(request.getElementId()))
        .forEach(request -> streamed.add(request.getContentCardElementReqBody().getContent()));
    if (!streamed.isEmpty()) {
      return streamed.get(streamed.size() - 1);
    }
    return spendColumnOf(lastSpendRow()).path("content").asString();
  }

  /** The spend row as it was last put on the card. */
  private JsonNode lastSpendRow() throws Exception {
    final var updates = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).update(updates.capture());
    final var rows =
        updates.getAllValues().stream()
            .filter(request -> "usage".equals(request.getElementId()))
            .toList();
    assertThat(rows).as("the spend row was never put on the card").isNotEmpty();
    return new JsonMapper()
        .readTree(rows.get(rows.size() - 1).getUpdateCardElementReqBody().getElement());
  }

  /** The element in that row the spend is written into, which is the first column's first. */
  private static JsonNode spendColumnOf(final JsonNode row) {
    return row.path("columns").path(0).path("elements").path(0);
  }

  private String lastContentOf(final String elementId) throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    final var footers =
        captor.getAllValues().stream()
            .filter(request -> elementId.equals(request.getElementId()))
            .toList();
    assertThat(footers).as("nothing was written to " + elementId).isNotEmpty();
    return footers.get(footers.size() - 1).getContentCardElementReqBody().getContent();
  }

  /** The chat options a deployment stating an effort is configured with. */
  private static OpenAiChatProperties chatPropertiesWithEffort(final String effort) {
    final var properties = new OpenAiChatProperties();
    properties.setReasoningEffort(effort);
    return properties;
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
