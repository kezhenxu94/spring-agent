package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
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
 * A background firing is one that may well have nothing to say, so the card that would announce it
 * is exactly the message its author did not want — but a firing that fell over cannot say so
 * itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuCardListenerBackgroundTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  private FeishuCardListener listener;

  @BeforeEach
  void setUp() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    final var messageCard =
        new FeishuMessageCard(
            new JsonMapper(),
            messages,
            new FeishuCardElements(
                new JsonMapper(),
                messages,
                new ClassPathResource("feishu/card-elements.json"),
                null),
            new ClassPathResource("feishu/reply-card.json"));
    listener =
        new FeishuCardListener(
            feishu,
            new JsonMapper(),
            null,
            mock(RestTemplate.class),
            messages,
            mock(UserWorkspaceFactory.class),
            null,
            null,
            null,
            null,
            null,
            messageCard,
            null);
  }

  private AgentRunRegistry registryFor(final boolean background) {
    return new AgentRunRegistry(
        AgentRequest.builder()
            .requestId("task-1")
            .scenario(BuiltInScenarios.SCHEDULED_TASK)
            .userId("ou_1")
            .rootMessageId("om_root")
            .replyMessageId("om_root")
            .background(background)
            .userMessage(spec -> spec.text("do the thing"))
            .build());
  }

  @Test
  @DisplayName("a background run gets no card, so a firing with nothing to say sends nothing")
  void backgroundRunCreatesNoCard() {
    listener.onStart(registryFor(true));

    verifyNoInteractions(feishu);
  }

  @Test
  @DisplayName("a background run that fails says so once, on the message the task was created in")
  void backgroundFailureIsReported() throws Exception {
    final var ok = new ReplyMessageResp();
    ok.setCode(0);
    when(feishu.im().v1().message().reply(any(ReplyMessageReq.class))).thenReturn(ok);

    final var run = attachedListener();
    run.onError(new IllegalStateException("the tool blew up"));
    run.onFinished(AgentOutcome.FAILED);

    final var captor = ArgumentCaptor.forClass(ReplyMessageReq.class);
    verify(feishu.im().v1().message()).reply(captor.capture());
    assertThat(captor.getValue().getMessageId()).isEqualTo("om_root");
    final var body = captor.getValue().getReplyMessageReqBody();
    assertThat(body.getMsgType()).isEqualTo("interactive");
    // The same card an answer arrives in, with the failure quoted rather than passed off as prose
    // the agent wrote.
    assertThat(body.getContent()).contains("> the tool blew up");
    assertThat(body.getContent()).contains("\"streaming_mode\":false");
  }

  @Test
  @DisplayName("a background run that succeeds stays silent")
  void backgroundSuccessIsSilent() throws Exception {
    attachedListener().onFinished(AgentOutcome.COMPLETED);

    verify(feishu.im().v1().message(), never()).reply(any(ReplyMessageReq.class));
  }

  /**
   * The per-run listener the background branch registers. Reached through a stub registry rather
   * than the real one, whose listener list is package-private to core.
   */
  private AgentResponseListener attachedListener() {
    final var registry = mock(AgentRunRegistry.class);
    when(registry.request()).thenReturn(registryFor(true).request());
    listener.onStart(registry);

    final var captor = ArgumentCaptor.forClass(AgentResponseListener.class);
    verify(registry).addResponseListener(captor.capture());
    return captor.getValue();
  }
}
