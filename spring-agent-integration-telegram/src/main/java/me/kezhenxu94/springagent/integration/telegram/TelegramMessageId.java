package me.kezhenxu94.springagent.integration.telegram;

import java.util.Optional;

/**
 * One message, as {@code <chat>:<message>}.
 *
 * <p>A Telegram message id is a counter per chat, so it names a message only together with its
 * chat. This is the request id of a run started by a message, which is what lets a reaction find
 * the message a run reports it read without anything having remembered where it was — the run
 * reports by request id alone.
 */
public record TelegramMessageId(String chatId, int messageId) {

  public String key() {
    return chatId + ":" + messageId;
  }

  public static Optional<TelegramMessageId> parse(final String key) {
    if (key == null) {
      return Optional.empty();
    }
    final var colon = key.lastIndexOf(':');
    if (colon <= 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new TelegramMessageId(
              key.substring(0, colon), Integer.parseInt(key.substring(colon + 1))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** {@code value} as a message id, or null where it is not one — a conversation key, say. */
  public static Integer messageIdOrNull(final String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
