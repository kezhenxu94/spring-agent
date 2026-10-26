package me.kezhenxu94.springagent.integration.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import me.kezhenxu94.springagent.core.preferences.UserPreferences;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Chat;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.MessageEntity;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.User;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAccess;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramIdentity;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import me.kezhenxu94.springagent.integration.telegram.greeting.TelegramGreetings;
import me.kezhenxu94.springagent.integration.telegram.usermodels.TelegramConfigHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The decisions this handler makes before a run starts, and the order it makes them in — see the
 * Slack handler's test for why each is worth more than a dropped message.
 */
class TelegramMessageReceiveHandlerTest {

  private static final long BOT = 42;
  private static final long PERSON = 7;
  private static final Chat PRIVATE = new Chat(PERSON, "private", null, null, null);
  private static final Chat GROUP = new Chat(-100123, "supergroup", "G", null, false);

  private final SpringAgent springAgent = mock(SpringAgent.class);
  private final ProcessedMessageRepo processedMessages = mock(ProcessedMessageRepo.class);
  private final PendingQuestionRepo pendingQuestions = mock(PendingQuestionRepo.class);
  private final TelegramChatObservations observations = mock(TelegramChatObservations.class);
  private final TelegramMessageText messageText = mock(TelegramMessageText.class);
  private final TelegramAccess access = mock(TelegramAccess.class);
  private final TelegramGreetings greetings = mock(TelegramGreetings.class);
  private final TelegramBotApi telegram = mock(TelegramBotApi.class);

  private static TelegramIdentity identity() {
    final var identity = mock(TelegramIdentity.class);
    when(identity.botId()).thenReturn(BOT);
    when(identity.username()).thenReturn("the_bot");
    when(identity.tenantId()).thenReturn(String.valueOf(BOT));
    when(identity.isMe(argThat(m -> m != null && m.equalsIgnoreCase("@the_bot")))).thenReturn(true);
    return identity;
  }

  private final TelegramMessageReceiveHandler handler =
      new TelegramMessageReceiveHandler(
          identity(),
          access,
          springAgent,
          pendingQuestions,
          processedMessages,
          mock(TelegramQuestionFormCloser.class),
          observations,
          messageText,
          new ScenarioMemos(List.of()),
          new UserPreferences(noPreferences(), new ScenarioMemos(List.of())),
          greetings,
          telegram,
          new TelegramMessages(
              new TelegramProperties(
                  "1:t", null, Locale.ENGLISH, null, null, null, null, null, null)),
          new ObjectProvider<TelegramConfigHandler>() {
            @Override
            public TelegramConfigHandler getObject() {
              return null;
            }
          });

  @BeforeEach
  void accepting() {
    when(springAgent.accepting()).thenReturn(true);
    when(processedMessages.claim(anyString())).thenReturn(true);
    when(pendingQuestions.findByConversationIdAndStatus(anyString(), any())).thenReturn(List.of());
    when(access.admits(any(), any())).thenReturn(true);
  }

  private static Message message(
      final Chat chat, final String text, final List<MessageEntity> entities) {
    return new Message(
        9,
        null,
        null,
        new User(PERSON, false, "Ann", null, "ann"),
        chat,
        0,
        null,
        text,
        entities,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private AgentRequest fired() {
    final var captor = ArgumentCaptor.forClass(AgentRequest.class);
    verify(springAgent).fireOrQueue(captor.capture(), any(), any());
    return captor.getValue();
  }

  @Test
  @DisplayName("a private message is answered, in a conversation keyed by the chat")
  void shouldAnswerAPrivateMessage() {
    handler.onMessage(1000, message(PRIVATE, "hello", List.of()));

    final var request = fired();
    assertThat(request.conversationId()).isEqualTo("telegram:" + PERSON);
    assertThat(request.rootMessageId()).isEqualTo(request.conversationId());
    assertThat(request.replyMessageId()).isEqualTo("9");
    assertThat(request.requestId()).isEqualTo(PERSON + ":9");
    assertThat(request.chatType()).isEqualTo("p2p");
    verify(processedMessages).claim("telegram:1000");
  }

  @Test
  @DisplayName("a group message nobody addressed to the bot is observed rather than answered")
  void shouldObserveAnUnaddressedGroupMessage() {
    handler.onMessage(1000, message(GROUP, "lunch?", List.of()));

    verify(observations).observed(any(), anyLong());
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
    verify(processedMessages, never()).claim(anyString());
  }

  @Test
  @DisplayName("a group message mentioning the bot is answered, in that person's own conversation")
  void shouldAnswerAMention() {
    handler.onMessage(
        1000, message(GROUP, "@the_bot why?", List.of(MessageEntity.of("mention", 0, 8))));

    final var request = fired();
    assertThat(request.conversationId()).isEqualTo("telegram:-100123:" + PERSON);
    assertThat(request.groupId()).isEqualTo("-100123");
    verify(observations, never()).observed(any(), anyLong());
  }

  @Test
  @DisplayName("somebody not admitted starts nothing, and claims nothing")
  void shouldRefuseSomebodyNotAdmitted() {
    when(access.admits(any(), any())).thenReturn(false);

    handler.onMessage(1000, message(PRIVATE, "hello", List.of()));

    verify(access).refuse(any(), any());
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
    verify(processedMessages, never()).claim(anyString());
  }

  @Test
  @DisplayName("a message from another bot is dropped, since nobody watches two bots talking")
  void shouldIgnoreBots() {
    final var fromBot =
        new Message(
            9,
            null,
            null,
            new User(99, true, "Other", null, null),
            PRIVATE,
            0,
            null,
            "hi",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    handler.onMessage(1000, fromBot);

    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("while shutting down the update is left unconfirmed rather than claimed")
  void shouldLeaveAShutdownMessageForTheNextPoll() {
    when(springAgent.accepting()).thenReturn(false);

    assertThatThrownBy(() -> handler.onMessage(1000, message(PRIVATE, "hello", List.of())))
        .isInstanceOf(TelegramMessageReceiveHandler.NotAccepting.class);
    verify(processedMessages, never()).claim(anyString());
  }

  @Test
  @DisplayName("the claim is released when nothing went on to answer the message")
  void shouldReleaseTheClaimOnFailure() {
    when(springAgent.fireOrQueue(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    handler.onMessage(1000, message(PRIVATE, "hello", List.of()));

    verify(processedMessages).release("telegram:1000");
  }

  @Test
  @DisplayName("/start greets rather than starting a run")
  void shouldGreetOnStart() {
    handler.onMessage(
        1000, message(PRIVATE, "/start", List.of(MessageEntity.of("bot_command", 0, 6))));

    verify(greetings).greet(String.valueOf(PERSON), String.valueOf(PERSON));
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName(
      "/config never reaches the agent, even where model settings are off — it may carry a token")
  void shouldNeverHandConfigToTheAgent() {
    handler.onMessage(1000, message(PRIVATE, "/config add token=sk-secret", List.of()));

    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName(
      "a memo picked from a group's command menu, with the bot's name on it, still selects")
  void shouldReadAMemoWithTheBotsName() {
    final var memos = new ScenarioMemos(List.of());
    final var normalized = handler.normalized("/kb@The_Bot what is our policy?");

    assertThat(normalized).isEqualTo("/kb what is our policy?");
    assertThat(memos.parse(normalized, BuiltInScenarios.CHAT).scenario())
        .isEqualTo(BuiltInScenarios.KNOWLEDGE_BASE);
  }

  private static UserPreferenceRepo noPreferences() {
    return new UserPreferenceRepo() {
      @Override
      public UserPreference save(final UserPreference preference) {
        return preference;
      }

      @Override
      public Optional<UserPreference> findById(final String id) {
        return Optional.empty();
      }

      @Override
      public void deleteById(final String id) {}
    };
  }
}
