package me.kezhenxu94.springagent.integration.slack.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.model.event.MessageEvent;
import java.util.List;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.slack.config.SlackIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decisions this handler makes before anything else happens, and the order it makes them in.
 *
 * <p>Each of these is a failure that costs more than a dropped message: answering the agent's own
 * message loops forever, and claiming a delivery too early turns a shutdown into a message nobody
 * ever answers.
 */
class SlackMessageReceiveHandlerTest {

  private static final String BOT = "U0BOT";

  private final SpringAgent springAgent = mock(SpringAgent.class);
  private final ProcessedMessageRepo processedMessages = mock(ProcessedMessageRepo.class);
  private final PendingQuestionRepo pendingQuestions = mock(PendingQuestionRepo.class);
  private final SlackQuestionFormCloser formCloser = mock(SlackQuestionFormCloser.class);
  private final SlackChatObservations observations = mock(SlackChatObservations.class);
  private final SlackMessageText messageText = mock(SlackMessageText.class);
  private final SlackUserNames userNames = mock(SlackUserNames.class);
  private final SlackMessageReactions reactions = mock(SlackMessageReactions.class);

  private static SlackIdentity identity() {
    final var identity = mock(SlackIdentity.class);
    when(identity.botUserId()).thenReturn(BOT);
    when(identity.teamId()).thenReturn("T0TEAM");
    return identity;
  }

  private final SlackMessageReceiveHandler handler =
      new SlackMessageReceiveHandler(
          identity(),
          springAgent,
          pendingQuestions,
          processedMessages,
          formCloser,
          observations,
          messageText,
          userNames,
          reactions);

  @BeforeEach
  void accepting() {
    when(springAgent.accepting()).thenReturn(true);
    when(processedMessages.claim(anyString())).thenReturn(true);
    when(pendingQuestions.findByConversationIdAndStatus(anyString(), any())).thenReturn(List.of());
    when(userNames.mentionsIn(anyString())).thenReturn("none");
  }

  private static MessageEvent message(final String channelType) {
    final var event = new MessageEvent();
    event.setChannel("C0CHANNEL");
    event.setChannelType(channelType);
    event.setUser("U0PERSON");
    event.setTeam("T0TEAM");
    event.setTs("1700000000.000100");
    event.setText("hello");
    return event;
  }

  @SuppressWarnings("unchecked")
  private static EventsApiPayload<MessageEvent> payload(final MessageEvent event) {
    final var payload = mock(EventsApiPayload.class);
    when(payload.getEvent()).thenReturn(event);
    when(payload.getEventId()).thenReturn("Ev0DELIVERY");
    return (EventsApiPayload<MessageEvent>) payload;
  }

  private void handle(final MessageEvent event) {
    final var ctx = mock(EventContext.class);
    handler.onMessage(payload(event), ctx);
  }

  @Test
  @DisplayName("a message the bot posted itself is dropped, or the agent answers itself forever")
  void shouldIgnoreItsOwnMessagesByUser() {
    final var own = message("im");
    own.setUser(BOT);

    handle(own);

    verify(springAgent, never()).fireOrQueue(any(), any(), any());
    // Not even claimed: a message nothing will answer has nothing to claim.
    verify(processedMessages, never()).claim(anyString());
  }

  @Test
  @DisplayName("and so is one carrying a bot id, which is how an integration's post arrives")
  void shouldIgnoreItsOwnMessagesByBotId() {
    final var own = message("im");
    own.setUser(null);
    own.setBotId("B0BOT");

    handle(own);

    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("a direct message is answered without needing a mention")
  void shouldAnswerADirectMessage() {
    handle(message("im"));

    verify(springAgent).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("a channel message with no mention is observed rather than answered")
  void shouldObserveAnUnaddressedChannelMessage() {
    handle(message("channel"));

    verify(observations).observed(any(), anyString());
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
    // Nothing is answering it, so there is nothing to claim.
    verify(processedMessages, never()).claim(anyString());
  }

  @Test
  @DisplayName("a channel message that mentions the bot is answered")
  void shouldAnswerWhenMentioned() {
    final var mentioned = message("channel");
    mentioned.setText("hey <@" + BOT + "> what is this");

    handle(mentioned);

    verify(springAgent).fireOrQueue(any(), any(), any());
    verify(observations, never()).observed(any(), anyString());
  }

  @Test
  @DisplayName("a redelivery that loses the claim is not answered a second time")
  void shouldIgnoreARedelivery() {
    when(processedMessages.claim(anyString())).thenReturn(false);

    handle(message("im"));

    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("a message arriving during shutdown is left unclaimed, so Slack sends it again")
  void shouldLeaveAShutdownMessageForTheNextAttempt() {
    when(springAgent.accepting()).thenReturn(false);

    handle(message("im"));

    // The claim is what would make the refusal permanent, so it must not have been taken.
    verify(processedMessages, never()).claim(anyString());
    verify(springAgent, never()).fireOrQueue(any(), any(), any());
  }

  @Test
  @DisplayName("the claim is released when nothing went on to answer the message")
  void shouldReleaseTheClaimOnFailure() {
    when(springAgent.fireOrQueue(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    handle(message("im"));

    verify(processedMessages).release("slack:Ev0DELIVERY");
  }

  @Test
  @DisplayName("the delivery id is namespaced, since the repo is shared with every other source")
  void shouldNamespaceTheDeliveryId() {
    handle(message("im"));

    verify(processedMessages).claim("slack:Ev0DELIVERY");
  }

  @Test
  @DisplayName("a thread reply shares the thread's conversation, so it shares chat memory")
  void shouldGroupAThreadIntoOneConversation() {
    final var reply = message("im");
    reply.setThreadTs("1699999999.000001");

    final var captor =
        org.mockito.ArgumentCaptor.forClass(
            me.kezhenxu94.springagent.core.agent.AgentRequest.class);
    handle(reply);

    verify(springAgent).fireOrQueue(captor.capture(), any(), any());
    assertThat(captor.getValue().conversationId()).isEqualTo("1699999999.000001");
    assertThat(captor.getValue().replyMessageId()).isEqualTo("1700000000.000100");
  }
}
