package me.kezhenxu94.springagent.integration.slack.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That every message this module writes exists in every language it claims to speak.
 *
 * <p>A missing key does not throw: {@code SlackMessages#get} falls back to the key itself, so the
 * failure is an English identifier appearing in a Chinese conversation. Nothing at runtime reports
 * it, which is why it is asserted here.
 */
class SlackMessagesTest {

  private static Properties bundle(final String name) throws IOException {
    final var properties = new Properties();
    try (InputStream in = SlackMessagesTest.class.getResourceAsStream("/slack/" + name)) {
      assertThat(in).as("%s is on the classpath", name).isNotNull();
      properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }

  @Test
  @DisplayName("the Chinese bundle translates every key the English one has, and invents none")
  void shouldTranslateEveryKey() throws IOException {
    final Set<String> english = bundle("messages.properties").stringPropertyNames();
    final Set<String> chinese = bundle("messages_zh_CN.properties").stringPropertyNames();

    assertThat(chinese).containsExactlyInAnyOrderElementsOf(english);
  }

  @Test
  @DisplayName("no message is left empty, which would render as a blank line rather than a label")
  void shouldNeverBeBlank() throws IOException {
    for (final var name : Set.of("messages.properties", "messages_zh_CN.properties")) {
      final var properties = bundle(name);
      for (final var key : properties.stringPropertyNames()) {
        assertThat(properties.getProperty(key)).as("%s in %s", key, name).isNotBlank();
      }
    }
  }

  @Test
  @DisplayName("a message resolves in the configured language rather than the host's")
  void shouldSpeakTheConfiguredLanguage() {
    final var chinese = new SlackMessages(properties(Locale.SIMPLIFIED_CHINESE));

    assertThat(chinese.get("message-stop")).isEqualTo("停止");
  }

  @Test
  @DisplayName("and falls back to English rather than to whatever the host happens to be")
  void shouldFallBackToEnglish() {
    final var unsupported = new SlackMessages(properties(Locale.forLanguageTag("fr")));

    assertThat(unsupported.get("message-stop")).isEqualTo("Stop");
  }

  @Test
  @DisplayName("an unknown key is returned as itself rather than throwing mid-run")
  void shouldNotThrowOnAnUnknownKey() {
    assertThat(new SlackMessages(properties(Locale.ENGLISH)).get("no-such-key"))
        .isEqualTo("no-such-key");
  }

  private static SlackProperties properties(final Locale locale) {
    return new SlackProperties("xoxb", "xapp", "U0BOT", "T0TEAM", locale, null, null);
  }
}
