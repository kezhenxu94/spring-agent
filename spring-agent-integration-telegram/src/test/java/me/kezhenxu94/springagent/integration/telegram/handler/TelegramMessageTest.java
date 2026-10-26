package me.kezhenxu94.springagent.integration.telegram.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramApiException;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Chat;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That a reply grows into as many messages as it needs, and rewrites only what changed. */
class TelegramMessageTest {

  private final TelegramBotApi telegram = mock(TelegramBotApi.class);

  private TelegramMessage message() {
    return new TelegramMessage(
        telegram,
        "1",
        null,
        5,
        100,
        TelegramText.plain("…"),
        Duration.ZERO,
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private static Message posted(final int id) {
    return new Message(
        id,
        null,
        null,
        null,
        new Chat(1, "private", null, null, null),
        0,
        null,
        "x",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName(
      "a reply longer than one message carries on in a second, with the buttons on the last")
  void shouldContinueInANewMessage() {
    when(telegram.sendMessage(any(), any(), any(), any(), any())).thenReturn(posted(101));
    final var message = message();
    final var paragraph = "x".repeat(3000);

    message.await(TelegramText.plain(paragraph + "\n\n" + paragraph), InlineKeyboardMarkup.none());

    assertThat(message.ids()).containsExactly(100, 101);
    assertThat(message.lastId()).isEqualTo(101);
    verify(telegram).editMessageText(eq("1"), eq(100), any(), eq(InlineKeyboardMarkup.none()));
    // Replying to the same message the reply answers, so a group can tell whose it is.
    verify(telegram).sendMessage(eq("1"), eq(null), any(), eq(5), any());
  }

  @Test
  @DisplayName("a page that has not changed is not written again")
  void shouldNotRewriteAFullPage() {
    when(telegram.sendMessage(any(), any(), any(), any(), any())).thenReturn(posted(101));
    final var message = message();
    final var first = "x".repeat(3998);

    message.await(TelegramText.plain(first + "\n\nmore"), InlineKeyboardMarkup.none());
    message.await(TelegramText.plain(first + "\n\nmore and more"), InlineKeyboardMarkup.none());

    // Once to write it; the second write changed only the page after it.
    verify(telegram, times(1)).editMessageText(eq("1"), eq(100), any(), any());
  }

  @Test
  @DisplayName("an edit Telegram calls a no-op counts as written, and is not retried")
  void shouldAcceptANoOpEdit() {
    doThrow(
            new TelegramApiException(
                "editMessageText", 400, "Bad Request: message is not modified", null))
        .when(telegram)
        .editMessageText(any(), anyInt(), any(), any());
    final var message = message();

    message.await(TelegramText.plain("same"), InlineKeyboardMarkup.none());
    message.await(TelegramText.plain("same"), InlineKeyboardMarkup.none());

    verify(telegram, times(1)).editMessageText(any(), anyInt(), any(), any());
    verify(telegram, never()).sendMessage(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a rate limit is slept out and the write made again, rather than dropped")
  void shouldRetryAfterARateLimit() {
    doThrow(
            new TelegramApiException(
                "editMessageText", 429, "Too Many Requests", Duration.ofMillis(10)))
        .doNothing()
        .when(telegram)
        .editMessageText(any(), anyInt(), any(), any());

    message().await(TelegramText.plain("hello"), InlineKeyboardMarkup.none());

    verify(telegram, times(2)).editMessageText(any(), anyInt(), any(), any());
  }
}
