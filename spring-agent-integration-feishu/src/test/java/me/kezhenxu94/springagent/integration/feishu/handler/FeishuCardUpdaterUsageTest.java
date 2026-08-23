package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import java.nio.file.Path;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
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
          new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH, null));

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    // The footer is one of the elements the card gains on first use, so writing it inserts it.
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    updater =
        FeishuCardUpdater.forRun(
            card,
            new JsonMapper(),
            // A tenth of a cent per thousand tokens each way, so the arithmetic is checkable.
            Map.of("the-model", new ModelPricing(1.0, 1.0, 1.0, Currency.USD)),
            messages,
            cardElements());
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

    assertThat(lastFooter()).isEqualTo("the-model");
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

  private String lastFooter() throws Exception {
    return lastContentOf("usage");
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

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements() {
    final var elements = new FeishuCardElements(new JsonMapper());
    elements.cardElements = new ClassPathResource("feishu/card-elements.json");
    return elements;
  }
}
