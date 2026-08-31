package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.response.Response;
import com.slack.api.model.event.MessageEvent;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.slack.config.SlackIdentity;
import me.kezhenxu94.springagent.integration.slack.usermodels.SlackConfigHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns a Slack message into an agent run.
 *
 * <p><b>The order of what happens here is load-bearing</b>, and the comments below say why each
 * step sits where it does rather than anywhere else. The first of them is the one with no analogue
 * anywhere else in this codebase: Slack delivers the bot's own messages back to the bot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackMessageReceiveHandler {

  /**
   * The namespace this surface claims delivery ids in. {@code ProcessedMessageRepo} is shared with
   * every other thing that claims one, and a Slack event id is only unique among Slack's.
   */
  private static final String CLAIM_PREFIX = "slack:";

  private final SlackIdentity identity;
  private final SpringAgent springAgent;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final ProcessedMessageRepo processedMessageRepo;
  private final SlackQuestionFormCloser questionFormCloser;
  private final SlackChatObservations chatObservations;
  private final SlackMessageText messageText;
  private final SlackUserNames userNames;

  /**
   * Told where this message lives, so a reaction later can find it.
   *
   * <p>Slack identifies a message by channel and timestamp together, but a run reports a queued
   * message by request id alone — which here is the timestamp. So the channel has to be recorded
   * now or there is nothing to react in later.
   */
  private final SlackMessageReactions reactions;

  /**
   * Absent unless {@code app.ai.user-models.encryption-key} is configured, which is the default.
   * Where it is absent {@code /config} is not a command at all and the message goes to the agent
   * like any other, which is the right answer: there is nothing to configure.
   */
  private final ObjectProvider<SlackConfigHandler> configHandler;

  /**
   * Marks anything the agent was still waiting to hear back on in this conversation as overtaken by
   * what just arrived, and takes its form off the message that carries it — a form the row behind
   * it no longer backs is a control that can only be pressed to be refused.
   *
   * <p>Best effort: a failure here costs a stale form the chance to be refused, not this message
   * the chance to be answered.
   */
  /** The command word that opens the model settings, as a message rather than a slash command. */
  private static final String CONFIG_COMMAND = "/config";

  /**
   * Whether this message is the {@code /config} command and nothing else.
   *
   * <p>Trimmed, which is the point: a real slash command never reaches this method, so what does is
   * somebody who typed a space first to get past Slack. Exact match rather than a prefix because
   * the command carries no arguments — a message that merely begins with the word is somebody
   * talking about it, and swallowing that would be the agent going silent on a question it should
   * have answered. The bot mention is stripped too, since in a channel the message arrives as
   * {@code <@U123> /config}.
   */
  private boolean isConfigCommand(final String text) {
    if (text == null) {
      return false;
    }
    return CONFIG_COMMAND.equalsIgnoreCase(text.replaceAll("<@[^>]+>", "").trim());
  }

  private void supersedePendingQuestions(final String conversationId) {
    try {
      pendingQuestionRepo
          .findByConversationIdAndStatus(conversationId, PendingQuestion.Status.PENDING)
          .forEach(
              pending -> {
                pendingQuestionRepo.updateStatus(pending.id(), PendingQuestion.Status.SUPERSEDED);
                try {
                  questionFormCloser.superseded(pending);
                } catch (Exception e) {
                  log.warn("Failed to close superseded question form {}", pending.id(), e);
                }
              });
    } catch (Exception e) {
      log.warn("Failed to supersede pending questions in conversation {}", conversationId, e);
    }
  }

  /**
   * Bolt hands us the parsed envelope and a context that has already acknowledged nothing yet — the
   * acknowledgement is the {@code ctx.ack()} at the end, and Slack drops a delivery that has not
   * been acknowledged within three seconds and sends it again. So nothing between here and there
   * may wait on anything slow: reading what the message carries is deferred into a supplier, and
   * everything else is a lookup or a write.
   */
  public Response onMessage(
      final com.slack.api.app_backend.events.payload.EventsApiPayload<MessageEvent> payload,
      final EventContext ctx) {
    final var event = payload.getEvent();
    try {
      handle(payload, event);
    } catch (Exception e) {
      // Logged rather than thrown. Bolt turns a thrown exception into a non-2xx acknowledgement,
      // which asks Slack to deliver the message again — and a message this application cannot make
      // sense of will fail the same way on every attempt. The one case that genuinely wants a
      // redelivery is shutdown, and that is handled where it is noticed, below.
      log.error("Failed to handle Slack message {} in {}", event.getTs(), event.getChannel(), e);
    }
    return ctx.ack();
  }

  private void handle(
      final com.slack.api.app_backend.events.payload.EventsApiPayload<MessageEvent> payload,
      final MessageEvent event) {
    // Slack delivers what this bot posts back to this bot, and nothing downstream would notice:
    // the agent would read its own answer as a new question and answer that, for ever. Checked
    // before anything else, including the delivery claim, because a message that is never going to
    // be answered has nothing to claim.
    //
    // Two tests rather than one, because a message can be the bot's in two ways that do not always
    // coincide: posted through an app or integration, which stamps bot_id and may leave user unset,
    // or posted as the bot user itself. Either is enough to drop it.
    //
    // The third way — Slack's `bot_message` subtype — needs no test here: AppConfig sets
    // subtypedMessageEventsAutoAckEnabled, so Bolt acknowledges every subtyped message itself and
    // this handler is only ever given one somebody typed.
    if (!Strings.isNullOrEmpty(event.getBotId()) || identity.botUserId().equals(event.getUser())) {
      log.debug("Ignoring the agent's own message {} in {}", event.getTs(), event.getChannel());
      return;
    }
    if (Strings.isNullOrEmpty(event.getUser())) {
      // Nobody said it, so there is nobody to answer and no identity a run could assume.
      return;
    }

    final var channelId = event.getChannel();
    final var messageId = event.getTs();
    // A thread's first message has no thread_ts, and is the root of the thread any reply to it
    // joins. So the conversation is the thread where there is one and this message where there is
    // not, which is what makes a reply share chat memory with what it replies to.
    final var rootId = Strings.isNullOrEmpty(event.getThreadTs()) ? messageId : event.getThreadTs();
    final var userId = event.getUser();
    final var tenantId =
        Strings.isNullOrEmpty(event.getTeam()) ? identity.teamId() : event.getTeam();
    // Slack's channel_type is im, mpim, group or channel. Only the first is a conversation with one
    // person; the rest are rooms, and this codebase calls a room "group".
    final var direct = "im".equalsIgnoreCase(event.getChannelType());
    final var chatType = direct ? "p2p" : "group";
    final var groupId = direct ? null : channelId;
    // Slack's own event id, which is stable across a redelivery of the same message and different
    // for the next one. The envelope carries it; the event does not.
    final var deliveryId =
        Strings.isNullOrEmpty(payload.getEventId()) ? messageId : payload.getEventId();

    log.info(
        "Received message: rootId={}, ts={}, channel={}, channelType={}, user={}, deliveryId={}",
        rootId,
        messageId,
        channelId,
        event.getChannelType(),
        userId,
        deliveryId);

    if (!springAgent.accepting()) {
      // Thrown, so that the acknowledgement fails and Slack delivers this again — to this process
      // once it is back, or to another that is still taking work.
      throw new IllegalStateException("Shutting down, ignoring message: " + messageId);
    }

    if (!direct && !isBotMentioned(event)) {
      // Nobody addressed the bot, so nothing answers this — and yet it is the case the agent is
      // watching a channel for: a question somebody asked the room that it may turn out to have a
      // solid answer to. So it is reported on the way past and the return stands. Whether any of it
      // is worth a word back is decided later and elsewhere, out of this thread and out of this
      // message's life, by whatever the observations accumulate into.
      chatObservations.observed(event, deliveryId);
      return;
    }

    // Slack redelivers an event it has not heard the acknowledgement for within three seconds, and
    // a reconnecting Socket Mode connection replays one — so a message can reach this method more
    // than once, and each time would otherwise start its own run and write its own answer.
    //
    // Placed exactly here, between the checks above and the effects below. After them, because
    // refusing a message while shutting down throws so that Slack will try again, and a claim taken
    // first would make that refusal permanent — while a channel message the bot was not mentioned
    // in is not being answered by anybody, so there is nothing to claim. Before them, because
    // superseding the outstanding questions changes the conversation, and firing answers it.
    if (!processedMessageRepo.claim(CLAIM_PREFIX + deliveryId)) {
      log.info("Ignoring message {}: it has already been taken up", deliveryId);
      return;
    }

    // Before anything that changes the conversation, and before the agent is involved at all: this
    // is what a user reaches for when the model they chose has stopped answering, so it must not
    // depend on a run succeeding.
    //
    // Reached as a message rather than as the slash command only when the command was never
    // declared on the Slack app — Slack keeps a real /config for itself and never delivers it here.
    // A message beginning with a space is sent verbatim, which is why " /config" arrives at all,
    // and it is the escape hatch for a workspace where the setup step was missed.
    final var config = configHandler.getIfAvailable();
    if (config != null && isConfigCommand(event.getText())) {
      log.info("Opening the model settings for {} in {}", userId, channelId);
      config.open(channelId, userId);
      return;
    }

    try {
      // Whatever this message says, it is the user's answer to anything the agent was still waiting
      // on in this conversation — they replied instead of using the form. Closing those off now
      // stops the form, pressed later, from starting a second run with an answer that has been
      // overtaken.
      supersedePendingQuestions(rootId);

      reactions.track(messageId, channelId);

      final var mentionsText = userNames.mentionsIn(event.getText());

      // Produced only when it is needed, and never on this thread: turning a message into text can
      // mean downloading what it carries, and Slack concludes a message it is still waiting on was
      // never delivered and sends it again. Assembly happens off this thread for that very reason
      // (see SpringAgent#fire), and a message queued onto a run already going is read on the thread
      // of the tool call that reads it.
      final Supplier<String> text = () -> messageText.of(event, userId);

      // Queued rather than fired where the user is already being answered in this conversation: a
      // second run would mean a second reply, a second stop button and two runs writing the same
      // chat memory, when what they have almost always sent is a correction of the run they are
      // watching. The run reads it as soon as the tool calls it is waiting on have come back; where
      // it ends before that point, this is answered as a run of its own after all.
      springAgent.fireOrQueue(
          AgentRequest.builder()
              .requestId(messageId)
              .scenario(BuiltInScenarios.CHAT)
              .userId(userId)
              .chatId(channelId)
              .chatType(chatType)
              .groupId(groupId)
              .tenantId(tenantId)
              .conversationId(rootId)
              .rootMessageId(rootId)
              .replyMessageId(messageId)
              .promptVariables(
                  Map.of(
                      "mentions",
                      mentionsText,
                      "threadId",
                      Strings.nullToEmpty(event.getThreadTs()),
                      "parentId",
                      Strings.nullToEmpty(event.getParentUserId())))
              .userMessage(user -> user.text(text.get()))
              .build(),
          text,
          messageText.display(event));
    } catch (Throwable t) {
      // Released, because nothing has answered this message and nothing now will. Holding the claim
      // would turn a failure here into a message silently dropped — worse than the duplicate the
      // claim exists to prevent — and Slack will hand it to us again.
      release(deliveryId);
      throw t;
    }
  }

  /** Best effort: failing to let go of a claim costs a redelivery its run, not this one. */
  private void release(final String deliveryId) {
    try {
      processedMessageRepo.release(CLAIM_PREFIX + deliveryId);
    } catch (Exception e) {
      log.warn("Could not release the claim on message {}", deliveryId, e);
    }
  }

  /**
   * Whether this message addresses the bot.
   *
   * <p>Read out of the text rather than taken from an {@code app_mention} event, because
   * subscribing to that as well would deliver every mention twice under two different event ids —
   * which the delivery claim cannot recognise as one message. A mention is {@code <@U123>} in the
   * raw text, and the bot's own id is configuration precisely so that this comparison exists.
   */
  boolean isBotMentioned(final MessageEvent event) {
    final var text = event.getText();
    return text != null && text.contains("<@" + identity.botUserId() + ">");
  }
}
