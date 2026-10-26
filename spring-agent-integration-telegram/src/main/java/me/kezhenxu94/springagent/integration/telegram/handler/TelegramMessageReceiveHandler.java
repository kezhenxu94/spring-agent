package me.kezhenxu94.springagent.integration.telegram.handler;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.logging.RunMdc;
import me.kezhenxu94.springagent.core.preferences.UserPreferences;
import me.kezhenxu94.springagent.integration.telegram.TelegramConversation;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAccess;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramIdentity;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import me.kezhenxu94.springagent.integration.telegram.greeting.TelegramGreetings;
import me.kezhenxu94.springagent.integration.telegram.usermodels.TelegramConfigHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns a Telegram message into an agent run.
 *
 * <p><b>The order of what happens here is load-bearing</b>, for the reasons {@code
 * SlackMessageReceiveHandler} gives step by step, with one step that is Telegram's alone:
 * admission. Anybody on Telegram can message a bot, so whether this person may use it at all is
 * asked before anything is claimed or started — see {@link TelegramAccess}.
 *
 * <p>What it does not have to do is recognise the bot's own messages: Telegram never delivers a bot
 * what it sent. Messages from other bots are dropped anyway, since a bot replying to a bot is a
 * loop nobody is watching.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageReceiveHandler {

  /** The namespace this surface claims delivery ids in — see the Slack handler. */
  static final String CLAIM_PREFIX = "telegram:";

  private final TelegramIdentity identity;
  private final TelegramAccess access;
  private final SpringAgent springAgent;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final ProcessedMessageRepo processedMessageRepo;
  private final TelegramQuestionFormCloser questionFormCloser;
  private final TelegramChatObservations chatObservations;
  private final TelegramMessageText messageText;
  private final ScenarioMemos memos;
  private final UserPreferences preferences;
  private final TelegramGreetings greetings;
  private final TelegramBotApi telegram;
  private final TelegramMessages messages;

  /** Absent unless {@code app.ai.user-models.encryption-key} is configured. */
  private final ObjectProvider<TelegramConfigHandler> configHandler;

  /** Thrown so that the poll stops short of confirming this update, and it is delivered again. */
  public static final class NotAccepting extends RuntimeException {
    NotAccepting() {
      super("Shutting down; leaving the update for the next poll");
    }
  }

  /**
   * Handles {@code message}, which arrived as update {@code updateId}.
   *
   * @throws NotAccepting while shutting down, which the poller reads as "stop here"
   */
  public void onMessage(final long updateId, final Message message) {
    if (message.from() == null || message.from().isBot() || message.chat() == null) {
      return;
    }
    final var conversation = TelegramConversation.of(message);
    final var requestId =
        new TelegramMessageId(String.valueOf(message.chat().id()), message.messageId()).key();
    try (var ignored =
        RunMdc.of(requestId, conversation.key(), String.valueOf(message.from().id()))) {
      handle(updateId, message, conversation, requestId);
    } catch (NotAccepting e) {
      throw e;
    } catch (Exception e) {
      // Logged rather than thrown: a message this application cannot make sense of fails the same
      // way on every redelivery, and the poller would otherwise be stuck on it.
      log.error("Failed to handle Telegram message {}", requestId, e);
    }
  }

  private void handle(
      final long updateId,
      final Message message,
      final TelegramConversation conversation,
      final String requestId) {
    final var chat = message.chat();
    if ("channel".equals(chat.type())) {
      // A channel post has no person behind it to answer or to act for.
      return;
    }
    final var from = message.from();
    final var direct = chat.isPrivate();
    final var chatId = String.valueOf(chat.id());
    final var userId = String.valueOf(from.id());

    log.info(
        "Received message: id={}, chatType={}, user={}, update={}",
        requestId,
        chat.type(),
        userId,
        updateId);

    if (!springAgent.accepting()) {
      throw new NotAccepting();
    }

    if (!direct && !addressesTheBot(message)) {
      // Nobody addressed the bot — which is the case observing a group is for. Reported on the
      // way past, and nothing answers it.
      chatObservations.observed(message, updateId);
      return;
    }

    if (!access.admits(from, chat)) {
      access.refuse(from, chat);
      return;
    }

    if (!processedMessageRepo.claim(CLAIM_PREFIX + updateId)) {
      log.info("Ignoring update {}: it has already been taken up", updateId);
      return;
    }

    final var text = normalized(message.textOrCaption());

    // The two commands this surface answers itself rather than handing to the agent. Both before
    // anything touches the conversation, and /config before the agent is involved at all: it is
    // what somebody reaches for when the model they chose has stopped answering.
    if (direct && isCommand(text, "/start")) {
      greetings.greet(chatId, userId);
      return;
    }
    if (TelegramConfigHandler.isConfigCommand(text)) {
      final var config = configHandler.getIfAvailable();
      if (config != null) {
        config.onCommand(message, text);
        return;
      }
      // Not handed to the agent even here, unlike Slack's: what follows /config may well be a
      // token, and a message the agent reads is a message chat memory keeps.
      say(chatId, messages.get("config-unavailable"));
      return;
    }

    try {
      supersedePendingQuestions(conversation.key());

      final var chosen = memos.parse(text, preferences.scenarioFor(userId));
      final Supplier<String> assembled =
          () -> memos.strip(normalized(messageText.of(message, userId)), chosen.scenario());

      springAgent.fireOrQueue(
          AgentRequest.builder()
              .requestId(requestId)
              .scenario(chosen.scenario())
              .userId(userId)
              .chatId(chatId)
              .chatType(direct ? "p2p" : "group")
              .groupId(direct ? null : chatId)
              .tenantId(identity.tenantId())
              .conversationId(conversation.key())
              .rootMessageId(conversation.key())
              .replyMessageId(String.valueOf(message.messageId()))
              .promptVariables(
                  Map.of(
                      "mentions",
                      mentions(message),
                      "threadId",
                      conversation.threadId() == null
                          ? ""
                          : String.valueOf(conversation.threadId()),
                      "parentId",
                      message.replyToMessage() == null
                          ? ""
                          : String.valueOf(message.replyToMessage().messageId())))
              .userMessage(user -> user.text(assembled.get()))
              .build(),
          assembled,
          memos.strip(messageText.display(message), chosen.scenario()));
    } catch (Throwable t) {
      // Released, because nothing has answered this message and nothing now will.
      release(updateId);
      throw t;
    }
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

  private void say(final String chatId, final String text) {
    try {
      telegram.sendMessage(chatId, null, TelegramText.plain(text), null, null);
    } catch (Exception e) {
      log.warn("Could not answer in {}: {}", chatId, e.getMessage());
    }
  }

  private void release(final long updateId) {
    try {
      processedMessageRepo.release(CLAIM_PREFIX + updateId);
    } catch (Exception e) {
      log.warn("Could not release the claim on update {}", updateId, e);
    }
  }

  /**
   * Whether a group message is for the bot: it mentions the bot, replies to one of its messages, or
   * names it on a command. With privacy mode on a group sends the bot only the last two, so a bot
   * meant to answer mentions runs with it off — and this is then what keeps it from answering every
   * line in the room.
   */
  boolean addressesTheBot(final Message message) {
    final var reply = message.replyToMessage();
    if (reply != null && reply.from() != null && reply.from().id() == identity.botId()) {
      return true;
    }
    final var text = message.textOrCaption();
    if (text == null) {
      return false;
    }
    for (final var entity : message.entitiesOrCaptionEntities()) {
      if ("text_mention".equals(entity.type())
          && entity.user() != null
          && entity.user().id() == identity.botId()) {
        return true;
      }
      if (entity.end() > text.length()) {
        continue;
      }
      final var spanned = text.substring(entity.offset(), entity.end());
      if ("mention".equals(entity.type()) && identity.isMe(spanned)) {
        return true;
      }
      if ("bot_command".equals(entity.type()) && spanned.contains("@")) {
        if (identity.isMe(spanned.substring(spanned.indexOf('@')))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * {@code text} with this bot's name taken off every command that carries it. A group's command
   * menu sends {@code /kb@SomeBot}, and the memo is {@code /kb}: left as it is, choosing a scenario
   * from the menu would choose nothing.
   */
  String normalized(final String text) {
    if (text == null || identity.username().isEmpty()) {
      return text == null ? "" : text;
    }
    return Pattern.compile(
            "(/[A-Za-z0-9_]+)@" + Pattern.quote(identity.username()) + "\\b",
            Pattern.CASE_INSENSITIVE)
        .matcher(text)
        .replaceAll("$1");
  }

  /** Whether {@code text} is {@code command} and nothing else — see the Slack handler's /config. */
  static boolean isCommand(final String text, final String command) {
    return text != null && command.equalsIgnoreCase(text.strip());
  }

  /**
   * Everybody the message names other than the bot, as the model would write them back: a handle
   * for somebody who has one, a name and id for somebody who does not.
   */
  private String mentions(final Message message) {
    final var text = message.textOrCaption();
    final var found = new LinkedHashSet<String>();
    for (final var entity : message.entitiesOrCaptionEntities()) {
      if ("text_mention".equals(entity.type())
          && entity.user() != null
          && entity.user().id() != identity.botId()) {
        found.add(entity.user().displayName() + " (" + entity.user().id() + ")");
      } else if ("mention".equals(entity.type()) && text != null && entity.end() <= text.length()) {
        final var handle = text.substring(entity.offset(), entity.end());
        if (!identity.isMe(handle)) {
          found.add(handle);
        }
      }
    }
    return found.isEmpty() ? "none" : String.join(", ", found);
  }
}
