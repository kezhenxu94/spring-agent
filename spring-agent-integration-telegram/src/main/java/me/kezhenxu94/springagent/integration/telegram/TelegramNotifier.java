package me.kezhenxu94.springagent.integration.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.stereotype.Component;

/**
 * Says something to a Telegram chat with no run behind it — this deployment's {@link Notifier}.
 * Holds nothing but the client, for the reason {@code SlackNotifier} gives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramNotifier implements Notifier {

  /**
   * What goes between an {@code @} and the name after it, so that the two read as they did and
   * Telegram no longer sees a mention: a word joiner, which has no width and is not a letter.
   */
  private static final String UNLINK = "\u2060";

  /** Under Telegram's 4096 characters a message, as {@code TelegramMessage} cuts a reply. */
  private static final int PAGE_LIMIT = 4000;

  private final TelegramBotApi telegram;

  @Override
  public void send(final Route route, final String text) {
    if (route == null || route.isEmpty()) {
      return;
    }
    final var target = route.chatId();
    try {
      // Plain text: formatting could only stop it being sent. In as many messages as it takes —
      // a failure notice quotes its evidence, which runs well past the 4096 characters Telegram
      // holds in one, and a notice refused for its length is a failure nobody hears about.
      for (final var page : TelegramText.plain(text).pages(PAGE_LIMIT)) {
        telegram.sendMessage(target, null, page, null, null);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Could not send a notification to " + target, e);
    }
    log.info("Sent a notification to {}", target);
  }

  @Override
  public String surface() {
    return "telegram";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Telegram has no markup to escape in plain text, and this module sends nothing else, so the
   * one thing in somebody else's words that acts rather than renders is a handle: {@code
   * @someone} written into a group notifies them whether or not anybody meant it to. Every {@code
   * @} is followed by a word joiner, which leaves the text looking the same and the handle
   * unrecognised.
   */
  @Override
  public String quoted(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return text.replace("@", "@" + UNLINK);
  }
}
