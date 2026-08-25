package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardRespBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageRespBody;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
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
 * What the card holds the moment it is sent, which is not the same as what the template says: the
 * template is the card's frame, and every element a run puts on the card and takes off again comes
 * from the elements file — the stop button among them, which has to be there from the start.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuCardListenerCardTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private SpringAgentProperties appConfiguration;

  private final JsonMapper om = new JsonMapper();

  private FeishuCardListener listenerIn(final Locale locale) {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(null, null, null, null, null, null, null, locale, null, null));
    final var listener =
        new FeishuCardListener(
            feishu,
            om,
            appConfiguration,
            mock(RestTemplate.class),
            messages,
            mock(UserWorkspaceFactory.class),
            null,
            null,
            new FeishuSubagentPanel(om, messages),
            new FeishuCardElements(
                om, messages, new ClassPathResource("feishu/card-elements.json")),
            new FeishuMessageReactions(feishu),
            mock(FeishuMessageCard.class));
    listener.feishuReplyCard = new ClassPathResource("feishu/reply-card.json");
    return listener;
  }

  @BeforeEach
  void setUp() throws Exception {
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
  }

  @Test
  @DisplayName("the card is sent with its stop button, above the footer it is anchored to")
  void theCardIsCreatedWithItsStopButton() throws Exception {
    listenerIn(Locale.ENGLISH).onStart(registryForAChatRun());

    // The button is part of the card rather than inserted afterwards, since a card sent without one
    // is a run nobody can stop until the insert lands. Nothing else is there: what the run says,
    // what the user says back, its task list and what it spent are all added as it first has
    // something to put in them.
    assertThat(elementIdsOfTheCreatedCard()).containsExactly("stop", "guide");
  }

  @Test
  @DisplayName("the button is labelled in the workspace's language, wherever it is read from")
  void theStopButtonIsLocalised() throws Exception {
    listenerIn(Locale.of("zh", "CN")).onStart(registryForAChatRun());

    assertThat(createdCard()).contains("停止").doesNotContain("{stop}");
  }

  private List<String> elementIdsOfTheCreatedCard() throws Exception {
    final var elements = om.readTree(createdCard()).path("body").path("elements");
    return StreamSupport.stream(elements.spliterator(), false)
        .map(element -> element.path("element_id").asString())
        .toList();
  }

  private String createdCard() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardReq.class);
    verify(feishu.cardkit().v1().card()).create(captor.capture());
    return captor.getValue().getCreateCardReqBody().getData();
  }

  /**
   * A stub registry rather than the real one, whose listener list is package-private to core: this
   * is only here so that the run gets as far as being given a card.
   */
  private static AgentRunRegistry registryForAChatRun() {
    final var registry = mock(AgentRunRegistry.class);
    when(registry.request())
        .thenReturn(
            AgentRequest.builder()
                .requestId("run-1")
                .scenario(BuiltInScenarios.CHAT)
                .userId("ou_1")
                .rootMessageId("om_root")
                .userMessage(spec -> spec.text("do the thing"))
                .build());
    return registry;
  }
}
