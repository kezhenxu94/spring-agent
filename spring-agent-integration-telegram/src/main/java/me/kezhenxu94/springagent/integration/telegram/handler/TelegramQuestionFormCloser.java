package me.kezhenxu94.springagent.integration.telegram.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.stereotype.Component;

/**
 * Takes a question form's buttons off the message that carries them, outside any run — the run that
 * drew them ended when it asked. See {@code SlackQuestionFormCloser}.
 *
 * <p>Only the buttons, and the text left as it is, unlike Slack's closer, which replaces the
 * message: the question is under the answer that led to it, on the same message, and the answer is
 * worth keeping. Where the press that answered is to hand, what was chosen is added beneath, so a
 * group can see what was decided without opening the next reply.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramQuestionFormCloser {

  private final TelegramBotApi telegram;

  /** A later message overtook the question. */
  public void superseded(final PendingQuestion pending) {
    final var id = TelegramMessageId.messageIdOrNull(pending.cardId());
    if (id == null || pending.chatId() == null) {
      return;
    }
    try {
      telegram.editMessageReplyMarkup(pending.chatId(), id, InlineKeyboardMarkup.none());
    } catch (Exception e) {
      log.warn("Could not close the question form for {}: {}", pending.id(), e.getMessage());
    }
  }

  /** The question was answered on {@code shown}, the message as the press found it. */
  public void answered(
      final PendingQuestion pending, final Message shown, final TelegramText summary) {
    if (shown == null || shown.text() == null) {
      superseded(pending);
      return;
    }
    final var text =
        TelegramText.builder()
            .append(new TelegramText(shown.text(), shown.entities()))
            .text("\n\n")
            .append(summary)
            .build();
    try {
      telegram.editMessageText(
          String.valueOf(shown.chat().id()), shown.messageId(), text, InlineKeyboardMarkup.none());
    } catch (Exception e) {
      log.warn("Could not close the answered form for {}: {}", pending.id(), e.getMessage());
      superseded(pending);
    }
  }
}
