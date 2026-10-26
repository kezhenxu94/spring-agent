package me.kezhenxu94.springagent.integration.telegram;

import java.util.Optional;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;

/**
 * What one conversation is on Telegram, and the key its chat memory is kept under.
 *
 * <p><b>Not a thread, because Telegram has none outside a forum.</b> Slack and Feishu key a
 * conversation by the message that opened a thread, and a reply joins it by naming that root. A
 * Telegram reply names only the message it answers, and that message arrives without the one
 * <em>it</em> answered — so a chain of replies cannot be followed back to where it began without
 * storing every link of it, and a person in a private chat rarely replies to anything at all. So:
 *
 * <ul>
 *   <li>a private chat is one conversation, which is how every chat app presents a bot;
 *   <li>a group is one conversation per person, so that two people asking the bot things at once
 *       neither share a memory nor have one's message queued onto the other's run;
 *   <li>a forum topic is a group of its own, per person.
 * </ul>
 *
 * <p>How much of an endless private chat a run sees is {@code app.ai.memory.window}, as anywhere.
 *
 * <p>The key is also the {@code rootMessageId} a request carries, because that is what a scheduled
 * task fires back into as its conversation — so it has to say where to send to as well as which
 * memory to read. {@link #parse} is how the listener reads it back.
 */
public record TelegramConversation(String chatId, Integer threadId, String userId) {

  private static final String PREFIX = "telegram:";

  /** The conversation {@code message} belongs to. */
  public static TelegramConversation of(final Message message) {
    final var chatId = String.valueOf(message.chat().id());
    if (message.chat().isPrivate()) {
      return new TelegramConversation(chatId, null, null);
    }
    return new TelegramConversation(
        chatId,
        message.inTopic() ? message.messageThreadId() : null,
        message.from() == null ? null : String.valueOf(message.from().id()));
  }

  /** {@code telegram:<chat>[/<topic>][:<user>]}. */
  public String key() {
    return PREFIX
        + chatId
        + (threadId == null ? "" : "/" + threadId)
        + (userId == null ? "" : ":" + userId);
  }

  /** The conversation behind {@code key}, or empty where it is not one of these. */
  public static Optional<TelegramConversation> parse(final String key) {
    if (key == null || !key.startsWith(PREFIX)) {
      return Optional.empty();
    }
    final var rest = key.substring(PREFIX.length());
    final var colon = rest.indexOf(':');
    final var chatPart = colon < 0 ? rest : rest.substring(0, colon);
    final var userId = colon < 0 ? null : rest.substring(colon + 1);
    final var slash = chatPart.indexOf('/');
    try {
      return Optional.of(
          new TelegramConversation(
              slash < 0 ? chatPart : chatPart.substring(0, slash),
              slash < 0 ? null : Integer.valueOf(chatPart.substring(slash + 1)),
              userId));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
