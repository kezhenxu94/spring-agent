package me.kezhenxu94.springagent.integration.telegram.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That a message resolves in the configured language, and falls back to English. */
class TelegramMessagesTest {

  static TelegramProperties properties(final Locale locale) {
    return new TelegramProperties("1:t", null, locale, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("a message resolves in the configured language rather than the host's")
  void shouldSpeakTheConfiguredLanguage() {
    assertThat(new TelegramMessages(properties(Locale.SIMPLIFIED_CHINESE)).get("message-stop"))
        .isEqualTo("停止");
  }

  @Test
  @DisplayName("and falls back to English rather than to whatever the host happens to be")
  void shouldFallBackToEnglish() {
    assertThat(new TelegramMessages(properties(Locale.forLanguageTag("fr"))).get("message-stop"))
        .isEqualTo("Stop");
  }

  @Test
  @DisplayName("an argument is filled in, which an apostrophe in the message would have stopped")
  void shouldFormatArguments() {
    assertThat(new TelegramMessages(properties(Locale.ENGLISH)).get("access-refused", "42"))
        .endsWith("42");
  }
}
