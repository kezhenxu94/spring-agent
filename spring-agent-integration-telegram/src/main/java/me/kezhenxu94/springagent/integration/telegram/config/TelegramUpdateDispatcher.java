package me.kezhenxu94.springagent.integration.telegram.config;

import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.CallbackQuery;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Update;
import me.kezhenxu94.springagent.integration.telegram.greeting.TelegramSuggestions;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramKeyboards;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramMessageReceiveHandler;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramQuestionAnswerHandler;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramStopHandler;
import me.kezhenxu94.springagent.integration.telegram.usermodels.TelegramConfigHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every update this application does something with, and where it goes — what Bolt's routing is for
 * the Slack surface, which here is a switch, since a Telegram update is one of a few shapes and a
 * button is named by the prefix of its data.
 *
 * <p>Runs on the poll's thread, one update at a time and in order, so nothing here may wait on
 * anything slow: every handler either answers at once or hands the work to an executor, the way
 * Slack's three-second acknowledgement forces there.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramUpdateDispatcher {

  private final TelegramMessageReceiveHandler messages;
  private final TelegramStopHandler stops;
  private final TelegramQuestionAnswerHandler answers;
  private final TelegramSuggestions suggestions;
  private final TelegramAccess access;
  private final TelegramBotApi telegram;

  /** Absent unless {@code app.ai.user-models.encryption-key} is configured. */
  private final ObjectProvider<TelegramConfigHandler> configHandler;

  public void dispatch(final Update update) {
    if (update.message() != null) {
      messages.onMessage(update.updateId(), update.message());
      return;
    }
    if (update.callbackQuery() != null) {
      onPress(update.callbackQuery());
    }
  }

  private void onPress(final CallbackQuery query) {
    final var data = Strings.nullToEmpty(query.data());
    final var chat = query.message() == null ? null : query.message().chat();
    try {
      // A press starts a run, answers for somebody or changes their model, so it is admitted the
      // way a message is. Stop is the exception: it only ever ends the presser's own run, and
      // somebody since removed from the list should still be able to stop what they started.
      if (!data.startsWith(TelegramKeyboards.STOP) && !access.admits(query.from(), chat)) {
        access.refuse(query.from(), chat);
        telegram.answerCallbackQuery(query.id(), null);
        return;
      }
      if (data.startsWith(TelegramKeyboards.STOP)) {
        stops.handle(query);
      } else if (data.startsWith(TelegramKeyboards.ANSWER)
          || data.startsWith(TelegramKeyboards.SUBMIT)) {
        answers.handle(query);
      } else if (data.startsWith(TelegramKeyboards.SUGGESTION)) {
        suggestions.handle(query);
      } else if (data.startsWith(TelegramKeyboards.CONFIG)
          && configHandler.getIfAvailable() != null) {
        configHandler.getObject().onPress(query);
      } else {
        // Not a button this application drew, or one for a feature switched off since.
        telegram.answerCallbackQuery(query.id(), null);
      }
    } catch (Exception e) {
      log.error("Failed to handle a button press {}", data, e);
    }
  }
}
