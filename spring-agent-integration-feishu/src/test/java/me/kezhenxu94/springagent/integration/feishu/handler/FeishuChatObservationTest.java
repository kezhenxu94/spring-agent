package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.observing.EventIntake;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.usermodels.FeishuConfigHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * A group message nobody addressed the bot in used to be dropped where it was noticed. It is now
 * reported into the observation funnel instead — and must still start no run, which is the property
 * every case here asserts alongside whatever else it is about: the moment one of these turns into a
 * run, the bot answers a room that did not ask it anything.
 */
class FeishuChatObservationTest {

  private static final String BOT = "ou_bot";
  private static final String WATCHED = "oc_watched";
  private static final String UNWATCHED = "oc_unwatched";

  private SpringAgent springAgent;
  private EventIntake intake;
  private ProcessedMessageRepo processedMessageRepo;

  /** The ids the handler claimed, so a case can say the observation path claimed nothing at all. */
  private Set<String> claimed;

  @BeforeEach
  void setUp() {
    springAgent = mock(SpringAgent.class);
    when(springAgent.accepting()).thenReturn(true);
    intake = mock(EventIntake.class);
    claimed = new HashSet<>();
    processedMessageRepo = mock(ProcessedMessageRepo.class);
    when(processedMessageRepo.claim(anyString())).thenAnswer(i -> claimed.add(i.getArgument(0)));
  }

  /**
   * The handler with only what these cases reach: the observations, the claim and the agent. The
   * rest of what a run needs is never touched, because none of these start one — a case that did
   * would fail on a null rather than pass quietly.
   */
  private FeishuMessageReceiveHandler handler(
      final EventIntakes intakes, final String... observedChatIds) {
    final var properties =
        new FeishuProperties(
            null,
            null,
            null,
            null,
            null,
            BOT,
            null,
            Locale.ENGLISH,
            null,
            null,
            Set.of(observedChatIds));
    final var observations =
        new FeishuChatObservations(
            new JsonMapper(), properties, new FeishuMessages(properties), intakes);
    return new FeishuMessageReceiveHandler(
        new JsonMapper(),
        properties,
        null,
        springAgent,
        null,
        null,
        mock(PendingQuestionRepo.class),
        processedMessageRepo,
        null,
        observations,
        // No model settings card in these tests: /config is not a command without one, which is
        // also the default for a deployment that has not configured an encryption key.
        new ObjectProvider<>() {
          @Override
          public FeishuConfigHandler getObject() {
            return null;
          }
        });
  }

  private FeishuMessageReceiveHandler handler(final String... observedChatIds) {
    return handler(new EventIntakes(List.of(intake)), observedChatIds);
  }

  @SuppressWarnings("unchecked")
  /** An application that consumes observations nowhere at all, which is the default. */
  private static EventIntakes noIntakes() {
    return new EventIntakes(List.of());
  }

  private P2MessageReceiveV1 event(
      final String messageId,
      final String chatId,
      final String chatType,
      final boolean mentionBot) {
    final var message = new EventMessage();
    message.setMessageId(messageId);
    message.setChatId(chatId);
    message.setChatType(chatType);
    message.setMessageType("text");
    message.setContent("{\"text\":\"how do I rotate the gateway certificate?\"}");
    if (mentionBot) {
      final var mention = new MentionEvent();
      mention.setKey("@_user_1");
      mention.setName("Agent");
      final var id = new UserId();
      id.setOpenId(BOT);
      mention.setId(id);
      message.setMentions(new MentionEvent[] {mention});
    }

    final var senderId = new UserId();
    senderId.setOpenId("ou_alice");
    final var sender = new EventSender();
    sender.setSenderId(senderId);
    sender.setTenantKey("tenant-7");

    final var data = new P2MessageReceiveV1Data();
    data.setMessage(message);
    data.setSender(sender);
    final var event = new P2MessageReceiveV1();
    event.setEvent(data);
    return event;
  }

  private Observation observed() {
    final var captor = ArgumentCaptor.forClass(Observation.class);
    verify(intake).observe(captor.capture());
    return captor.getValue();
  }

  private void startedNoRun() {
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("a group message the bot was not mentioned in is observed, and answered by nobody")
  void observesWatchedGroupChat() throws Exception {
    handler(WATCHED).handle(event("om_1", WATCHED, "group", false));

    final var observation = observed();
    assertThat(observation.source()).isEqualTo("feishu-chat");
    assertThat(observation.deliveryId()).isEqualTo("om_1");
    assertThat(observation.kind()).isEqualTo("chat.message");
    assertThat(observation.correlationKey()).isEqualTo("feishu-chat:" + WATCHED);
    // Who spoke is evidence, and Observation has nowhere else to put it.
    assertThat(observation.summary()).contains("ou_alice", "rotate the gateway certificate");
    assertThat(observation.payloadJson()).contains("rotate the gateway certificate");
    assertThat(observation.route().chatId()).isEqualTo(WATCHED);
    assertThat(observation.route().chatType()).isEqualTo("group");
    assertThat(observation.route().groupId()).isEqualTo(WATCHED);
    assertThat(observation.route().tenantId()).isEqualTo("tenant-7");
    startedNoRun();
  }

  @Test
  @DisplayName("a chat nobody asked to be watched is not observed, and nothing is written")
  void ignoresChatThatWasNotNamed() throws Exception {
    handler(WATCHED).handle(event("om_2", UNWATCHED, "group", false));

    verifyNoInteractions(intake);
    // And nothing was claimed: observing a message takes no claim on it, so an unwatched chat
    // leaves no trace of any kind.
    assertThat(claimed).isEmpty();
    startedNoRun();
  }

  @Test
  @DisplayName("the default watches nothing at all")
  void observesNothingByDefault() throws Exception {
    handler().handle(event("om_3", WATCHED, "group", false));

    verifyNoInteractions(intake);
    startedNoRun();
  }

  @Test
  @DisplayName("a redelivery reports the same delivery id, dedup being the funnel's job")
  void reportsAStableDeliveryId() throws Exception {
    final var handler = handler(WATCHED);
    handler.handle(event("om_4", WATCHED, "group", false));
    handler.handle(event("om_4", WATCHED, "group", false));

    // What this module owes the funnel is at-least-once with an id that does not move: Feishu
    // redelivers an event it has not heard the acknowledgement for, so both attempts arrive here
    // and both are reported. Recognising the second for what it is happens in EventIntake, keyed on
    // exactly this id, once for every source rather than once per transport — so nothing here
    // claims anything, and the assertion is on the id being the same twice.
    final var captor = ArgumentCaptor.forClass(Observation.class);
    verify(intake, times(2)).observe(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(Observation::deliveryId)
        .containsExactly("om_4", "om_4");
    startedNoRun();
  }

  @Test
  @DisplayName("a group message that does mention the bot is answered, not observed")
  void doesNotObserveAMessageAddressedToTheBot() throws Exception {
    handler(WATCHED).handle(event("om_5", WATCHED, "group", true));

    verifyNoInteractions(intake);
    verify(springAgent).fireOrQueue(any(), any(), any());
    // The answer-claim, unchanged by any of this and the only claim a message is ever under.
    assertThat(claimed).containsExactly("om_5");
  }

  @Test
  @DisplayName("a p2p message is answered as before and never observed")
  void leavesP2pAlone() throws Exception {
    handler(WATCHED).handle(event("om_6", "oc_p2p", "p2p", false));

    verifyNoInteractions(intake);
    verify(springAgent).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("no EventIntake on the classpath changes nothing at all")
  void doesNothingWithoutTheModule() throws Exception {
    handler(noIntakes(), WATCHED).handle(event("om_7", WATCHED, "group", false));

    verifyNoInteractions(intake);
    assertThat(claimed).isEmpty();
    startedNoRun();
  }

  @Test
  @DisplayName("a funnel that throws costs an observation, not the chat")
  void survivesAFailingIntake() {
    org.mockito.Mockito.doThrow(new IllegalStateException("events backend down"))
        .when(intake)
        .observe(any());
    final var handler = handler(WATCHED);

    // Nothing may escape: the handler runs on the thread acknowledging the event, and an exception
    // here would have Feishu deliver the message again.
    assertThatCode(() -> handler.handle(event("om_8", WATCHED, "group", false)))
        .doesNotThrowAnyException();
    startedNoRun();
  }

  @Test
  @DisplayName("a message with nothing to read is not observed")
  void ignoresAMessageThatCarriesNoText() throws Exception {
    final var event = event("om_9", WATCHED, "group", false);
    final var message = event.getEvent().getMessage();
    message.setMessageType("image");
    message.setContent("{\"image_key\":\"img_v2_abc\"}");

    handler(WATCHED).handle(event);

    // Reading it would mean downloading it into a workspace, on this thread, for a message nobody
    // asked about.
    verifyNoInteractions(intake);
    startedNoRun();
  }

  @Test
  @DisplayName("a rich post is observed with its body, not just its title")
  void observesAPostInFull() throws Exception {
    final var event = event("om_10", WATCHED, "group", false);
    final var message = event.getEvent().getMessage();
    message.setMessageType("post");
    message.setContent(
        "{\"title\":\"Gateway outage\",\"content\":[[{\"tag\":\"text\",\"text\":\"the sidecar"
            + " keeps restarting\"}]]}");

    handler(WATCHED).handle(event);

    assertThat(observed().summary()).contains("Gateway outage", "the sidecar keeps restarting");
    startedNoRun();
  }

  @Test
  @DisplayName("the source name is the one the events module reads its settings under")
  void sourceNameIsTheAgreedLiteral() {
    // Coupled textually, the dependency being allowed to point only from here to core. Both halves
    // are spelled out so that renaming one is a failing test rather than a source that silently
    // falls onto the default policy.
    assertThat(FeishuChatObservations.SOURCE).isEqualTo("feishu-chat");
  }

  @Test
  @DisplayName("the words put around what was said are the workspace's, and what was said is not")
  void framesTheObservationInTheWorkspaceLanguage() {
    // These two strings reach the model inside the brief a triage run is given, so they are the
    // agent's own text and follow app.locale like the rest of it. What the person actually wrote is
    // an argument and goes in untouched — translating evidence would be inventing it.
    final var chinese = Locale.of("zh", "CN");
    final var properties =
        new FeishuProperties(
            null, null, null, null, null, BOT, null, chinese, null, null, Set.of(WATCHED));
    final var recorded = new java.util.ArrayList<Observation>();
    final var observations =
        new FeishuChatObservations(
            new JsonMapper(),
            properties,
            new FeishuMessages(properties),
            new EventIntakes(List.of(recorded::add)));

    observations.observed(
        event("om_9", WATCHED, "group", false).getEvent().getMessage(), "ou_alice", "tenant-1");

    assertThat(recorded).singleElement();
    final var observation = recorded.getFirst();
    assertThat(observation.title()).isEqualTo("飞书群聊 " + WATCHED);
    assertThat(observation.summary()).startsWith("ou_alice 说：");
    // Said in English, recorded in English, whatever language the workspace speaks.
    assertThat(observation.summary()).contains("rotate the gateway certificate");
  }
}
