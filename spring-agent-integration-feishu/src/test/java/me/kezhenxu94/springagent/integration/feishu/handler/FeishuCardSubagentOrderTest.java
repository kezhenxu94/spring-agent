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
import java.util.List;
import java.util.Locale;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where a subagent's panel sits on the card it is given one on: above the run's own tool calls, and
 * below everything the run says for itself. An insert names the element it lands above, so this is
 * decided by which anchor each one is given rather than by the order they arrive in — and the
 * subagents are the one place on the card that is not an element of it, so the anchor for them and
 * the anchor for anything above them are both worked out here.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardSubagentOrderTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  private final FeishuMessages messages =
      new FeishuMessages(
          new FeishuProperties(
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));

  private FeishuCardUpdater updater;

  @BeforeEach
  void setUp() throws Exception {
    final var streamed = new ContentCardElementResp();
    streamed.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(streamed);
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    updater =
        FeishuCardUpdater.forRun(
            new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages),
            om,
            null,
            messages,
            new FeishuCardElements(
                om, messages, new ClassPathResource("feishu/card-elements.json"), null),
            null);
  }

  @Test
  @DisplayName("a subagent's panel lands where the tool calls go, before the run has made one")
  void thePanelIsAnchoredWhereTheToolsPaneWillBe() {
    // The spend row is all a freshly sent card has, so that is what the panel clears — and the
    // tools pane, when the run makes its first call, is anchored on the same row and so lands
    // under the panel rather than over it.
    assertThat(updater.subagentPanelAnchor()).isEqualTo(FeishuCardElements.USAGE);
  }

  @Test
  @DisplayName("a subagent started after the first tool call still goes above the calls")
  void thePanelIsAnchoredOnTheToolsPane() throws Exception {
    updater.setToolStatus("Bash", "{}", null);

    assertThat(updater.subagentPanelAnchor()).isEqualTo(FeishuCardElements.TOOLS);
  }

  @Test
  @DisplayName("what the run says for itself goes in above the subagents, not among them")
  void theRunsOwnElementsClearThePanels() throws Exception {
    updater.subagentPanelAdded(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"));
    updater.subagentPanelAdded(FeishuSubagentPanel.panelElementId("sub_e5f6a7b8"));

    updater.onContent("here is what they found");

    // The topmost panel, so the answer clears every subagent — the second one landed under the
    // first and anchoring on it would put the answer between them.
    assertThat(insertTargets()).containsExactly(FeishuSubagentPanel.panelElementId("sub_a1b2c3d4"));
  }

  /** What each insert of the turn named as the element it lands above, oldest first. */
  private List<String> insertTargets() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    return captor.getAllValues().stream()
        .map(request -> request.getCreateCardElementReqBody().getTargetElementId())
        .toList();
  }
}
