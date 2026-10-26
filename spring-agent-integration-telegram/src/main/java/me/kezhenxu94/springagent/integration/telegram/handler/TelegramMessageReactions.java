package me.kezhenxu94.springagent.integration.telegram.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import org.springframework.stereotype.Component;

/**
 * What the agent puts on a message it cannot answer yet — see {@code SlackMessageReactions} for why
 * a reaction, on the message itself, is where somebody who just typed is looking.
 *
 * <p>Two differences from Slack, both Telegram's. A bot leaves one reaction on a message, so the
 * second replaces the first rather than joining it, and the progression reads as a change of state
 * rather than as two marks. And the reactions are from Telegram's fixed list, which has no check
 * mark: {@code 👀} when the message is queued, {@code 👌} when the run has read it.
 *
 * <p>Nothing is remembered between the two: a run reports a queued message by its request id, which
 * here names the chat and the message — see {@link TelegramMessageId}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageReactions {

  private static final String QUEUED = "👀";

  private static final String READ = "👌";

  private final TelegramBotApi telegram;

  public void queued(final String requestId) {
    react(requestId, QUEUED);
  }

  public void read(final String requestId) {
    react(requestId, READ);
  }

  private void react(final String requestId, final String emoji) {
    final var message = TelegramMessageId.parse(requestId).orElse(null);
    if (message == null) {
      return;
    }
    try {
      telegram.setMessageReaction(message.chatId(), message.messageId(), emoji);
    } catch (Exception e) {
      // A courtesy on top of the run, and a group may have reactions switched off entirely.
      log.debug("Could not react {} to {}: {}", emoji, requestId, e.getMessage());
    }
  }
}
