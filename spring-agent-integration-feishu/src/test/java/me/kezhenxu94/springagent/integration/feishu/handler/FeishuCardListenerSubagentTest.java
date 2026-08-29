package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardRespBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageRespBody;
import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A subagent has no message to reply a card onto, and a card of its own would put a second stop
 * button in front of a reader for work they never started. It goes onto the card of the run that
 * started it instead — which is also what lets its tool calls be announced, since the interceptor
 * looks the updater up in the run's tool context and a subagent had none.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuCardListenerSubagentTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private SpringAgentProperties appConfiguration;

  private FeishuCardListener listener;

  @BeforeEach
  void setUp() throws Exception {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    final var om = new JsonMapper();
    final var panels = new FeishuSubagentPanel(om, messages);
    panels.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    listener =
        new FeishuCardListener(
            feishu,
            om,
            appConfiguration,
            mock(RestTemplate.class),
            messages,
            mock(UserWorkspaceFactory.class),
            null,
            null,
            panels,
            // The parent's card is created here, and a card is created with its stop button.
            new FeishuCardElements(
                om, messages, new ClassPathResource("feishu/card-elements.json"), null),
            new FeishuMessageReactions(feishu),
            new FeishuMessageCard(
                om,
                messages,
                new FeishuCardElements(
                    om, messages, new ClassPathResource("feishu/card-elements.json"), null),
                new ClassPathResource("feishu/reply-card.json")),
            // Unthrottled, like every card whose interval is left unset: these tests assert on the
            // calls a write makes, not on when the card lets them out.
            null,
            null);
    listener.feishuReplyCard = new ClassPathResource("feishu/reply-card.json");

    final var created = new CreateCardResp();
    created.setCode(0);
    final var createdBody = new CreateCardRespBody();
    createdBody.setCardId("card-1");
    created.setData(createdBody);
    when(feishu.cardkit().v1().card().create(any(CreateCardReq.class))).thenReturn(created);

    final var replied = new ReplyMessageResp();
    replied.setCode(0);
    final var repliedBody = new ReplyMessageRespBody();
    repliedBody.setMessageId("om_card");
    replied.setData(repliedBody);
    when(feishu.im().v1().message().reply(any(ReplyMessageReq.class))).thenReturn(replied);

    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
  }

  @Test
  @DisplayName("a subagent gets a panel on its parent's card, and an updater its tools can find")
  void aSubagentIsGivenAPanelOnItsParentsCard() throws Exception {
    listener.onStart(registryFor(chatRequest()));

    final var registry = registryFor(subagentOf("run-1"));
    listener.onStart(registry);

    // The panel is inserted before the run is assembled, since nothing can be streamed into an
    // element the card does not have yet.
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement()).create(captor.capture());
    assertThat(captor.getValue().getCreateCardElementReqBody().getElements())
        .contains("Reading the timeline")
        .as("the brief the subagent was given is on its panel from the start")
        .contains("Read the incident timeline and say when it starts");
    // Where the run's tool calls go, which on a card that has made none yet is the spend row: the
    // pane is anchored there too when the first call comes, and so lands under the panel.
    assertThat(captor.getValue().getCreateCardElementReqBody().getTargetElementId())
        .as("a subagent's panel goes above the calls the run makes itself")
        .isEqualTo(FeishuCardElements.USAGE);

    // Under the same key as the run's own updater, so the tool interceptor announces a subagent's
    // calls in its panel exactly as it announces the run's on the card.
    verify(registry).addToolContext(eq(FeishuCardUpdater.TOOL_CONTEXT_KEY.key()), any());
    verify(registry).addResponseListener(any());

    // One card for the turn, not one per subagent.
    verify(feishu.cardkit().v1().card(), times(1)).create(any(CreateCardReq.class));
  }

  @Test
  @DisplayName("a subagent of a run this surface knows nothing about is left alone")
  void anUnknownParentMeansNoPanel() throws Exception {
    final var registry = registryFor(subagentOf("some-other-run"));
    listener.onStart(registry);

    verify(feishu.cardkit().v1().cardElement(), never()).create(any(CreateCardElementReq.class));
    verify(registry, never()).addToolContext(any(), any());
  }

  private static AgentRequest chatRequest() {
    return AgentRequest.builder()
        .requestId("run-1")
        .scenario(BuiltInScenarios.SCHEDULED_TASK)
        .userId("ou_1")
        .rootMessageId("om_root")
        .userMessage(spec -> spec.text("do the thing"))
        .build();
  }

  private static AgentRequest subagentOf(final String parentRequestId) {
    return AgentRequest.builder()
        .requestId("sub_1")
        .parentRequestId(parentRequestId)
        .description("Reading the timeline")
        .brief("Read the incident timeline and say when it starts")
        .scenario(BuiltInScenarios.SUBAGENT)
        .userId("ou_1")
        .background(true)
        .userMessage(spec -> spec.text("read it"))
        .build();
  }

  /**
   * A stub registry rather than the real one, whose listener list is package-private to core: what
   * is being asserted is what the listener contributes, which is only visible from this side.
   */
  private static AgentRunRegistry registryFor(final AgentRequest request) {
    final var registry = mock(AgentRunRegistry.class);
    when(registry.request()).thenReturn(request);
    return registry;
  }
}
