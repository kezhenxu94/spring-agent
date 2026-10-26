package me.kezhenxu94.springagent.integration.telegram.greeting;

import com.google.common.base.Strings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardButton;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAutoConfiguration;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramMarkdown;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramKeyboards;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Answers {@code /start}: the welcome note to somebody new, and to somebody coming back the notes
 * written since they last looked.
 *
 * <p>{@code SlackGreetings}' counterpart with one difference that follows from what triggers it.
 * Slack's event fires every time the conversation is opened, so a person who is up to date is told
 * nothing; {@code /start} is somebody asking, every time, so they are told something every time —
 * the notes they have not read where there are any, and the welcome where there are none. Every
 * Telegram client sends it on first opening a bot, which is the first contact Slack has to read off
 * a missing row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramGreetings {

  /** How many suggestions get a button. */
  private static final int SUGGESTIONS_SHOWN = 5;

  private final TelegramBotApi telegram;
  private final TelegramUpdates updates;
  private final TelegramMessages messages;
  private final SeenUpdateRepo seenUpdateRepo;

  @Qualifier(TelegramAutoConfiguration.TASK_EXECUTOR)
  private final TaskExecutor taskExecutor;

  /** Called from the poll's thread; returns as soon as the work is handed off. */
  public void greet(final String chatId, final String userId) {
    taskExecutor.execute(() -> send(chatId, userId));
  }

  private void send(final String chatId, final String userId) {
    try {
      final var current = updates.current();
      final var seen = seenUpdateRepo.findById(userId).orElse(null);
      final var unread =
          seen == null ? List.<TelegramUpdates.Note>of() : updates.since(seen.version());
      if (unread.isEmpty()) {
        welcome(chatId);
      } else {
        telegram.sendMessage(chatId, null, notes(unread), null, null);
      }
      seenUpdateRepo.save(
          SeenUpdate.builder().id(userId).version(current).updatedAt(Instant.now()).build());
      log.info("Greeted {} in {}, now at update {}", userId, chatId, current);
    } catch (Exception e) {
      log.error("Failed to greet {} in {}", userId, chatId, e);
    }
  }

  private void welcome(final String chatId) {
    final var welcome = updates.welcome();
    final var text = TelegramText.builder();
    if (!Strings.isNullOrEmpty(welcome.title())) {
      text.styled("bold", welcome.title()).text("\n\n");
    }
    text.append(TelegramMarkdown.render(welcome.body()));
    final var buttons = new ArrayList<InlineKeyboardButton>();
    final var suggestions = welcome.suggestions();
    for (var i = 0; i < Math.min(SUGGESTIONS_SHOWN, suggestions.size()); i++) {
      buttons.add(TelegramKeyboards.button(suggestions.get(i), TelegramKeyboards.SUGGESTION + i));
    }
    if (!buttons.isEmpty()) {
      text.text("\n\n").styled("italic", messages.get("welcome-suggest-hint"));
    }
    final var built = text.build();
    if (built.isBlank()) {
      // A deployment shipping no welcome greets with nothing, which is what an empty note means.
      return;
    }
    telegram.sendMessage(
        chatId, null, built, null, buttons.isEmpty() ? null : TelegramKeyboards.column(buttons));
  }

  private TelegramText notes(final List<TelegramUpdates.Note> notes) {
    final var text =
        TelegramText.builder().styled("bold", messages.get("update-title", notes.size()));
    for (final var note : notes) {
      text.text("\n\n").styled("italic", messages.get("update-version", note.version()));
      if (!Strings.isNullOrEmpty(note.title())) {
        text.text("  ").styled("bold", note.title());
      }
      text.text("\n").append(TelegramMarkdown.render(note.body()));
    }
    return text.build();
  }
}
