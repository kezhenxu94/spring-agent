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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the card holds the moment it is sent, which is not the same as what the template says: the
 * template is the card's frame, and every element a run puts on the card and takes off again comes
 * from the elements file — the spend row among them, which has to be there from the start because
 * the stop button rides in it.
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
                om, messages, new ClassPathResource("feishu/card-elements.json"), null),
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
  @DisplayName("the card is sent with the spend row the stop button rides in, above the hint")
  void theCardIsCreatedWithItsStopButton() throws Exception {
    listenerIn(Locale.ENGLISH).onStart(registryForAChatRun());

    // The row is part of the card rather than inserted afterwards, since a card sent without it is
    // a run nobody can stop until the insert lands. Nothing else is there: what the run says, what
    // the user says back, its task list and what it read are all added as it first has something to
    // put in them.
    assertThat(elementIdsOfTheCreatedCard()).containsExactly("usage", "guide");
  }

  @Test
  @DisplayName("the button is alone in the row until the run has something to say it has spent")
  void theStopButtonIsAloneOnTheRowUntilThereIsSpend() throws Exception {
    listenerIn(Locale.ENGLISH).onStart(registryForAChatRun());

    final var columns = spendRowOfTheCreatedCard().path("columns");
    // A column holding an empty line is still a column: with one there the row's spacing leaves the
    // button standing off the left edge as though something were beside it. It joins the row when
    // there is a line to put in it — FeishuCardUpdaterUsageTest covers that.
    final var button = columns.path(0).path("elements").path(0);
    assertThat(button.path("tag").asString()).isEqualTo("button");
    // Only as wide as what it holds, with an empty column taking the rest of the row, which is what
    // keeps the button at the left of the card rather than stretched across it.
    assertThat(columns.path(0).path("weight").asInt()).isZero();
    assertThat(columns.path(1).path("weight").asInt()).isEqualTo(1);
    assertThat(columns.path(1).path("elements")).isEmpty();
    // On the button rather than on the row, because the run ends by deleting this id and the spend
    // line has to survive it — see FeishuCard#finish().
    assertThat(button.path("element_id").asString()).isEqualTo("stop");
  }

  @Test
  @DisplayName("the button's label is the size of the spend it comes to sit beside")
  void theStopButtonIsSetAtTheSizeOfTheSpendLine() throws Exception {
    listenerIn(Locale.ENGLISH).onStart(registryForAChatRun());

    // The tiny button size is padding rather than type, so without this the two halves of one line
    // are set in two sizes.
    final var button = spendRowOfTheCreatedCard().path("columns").path(0).path("elements").path(0);
    assertThat(button.path("text").path("text_size").asString()).isEqualTo("notation");
  }

  private JsonNode spendRowOfTheCreatedCard() throws Exception {
    return StreamSupport.stream(
            om.readTree(createdCard()).path("body").path("elements").spliterator(), false)
        .filter(element -> "usage".equals(element.path("element_id").asString()))
        .findFirst()
        .orElseThrow();
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
