package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import me.kezhenxu94.springagent.appcli.config.CliProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class CliMessagesTest {

  private static final Locale CHINESE = Locale.of("zh", "CN");

  /** The English bundle, shared with the tests that assert on what a user actually sees. */
  static CliMessages english() {
    return messagesIn(Locale.ENGLISH);
  }

  static CliMessages messagesIn(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename("messages");
    source.setDefaultEncoding(StandardCharsets.UTF_8.name());
    source.setFallbackToSystemLocale(false);
    return new CliMessages(source, new CliProperties("kez", true, locale));
  }

  @Test
  void speaksTheLocaleItWasGiven() {
    assertThat(messagesIn(Locale.ENGLISH).get("stopping")).isEqualTo("Stopping.");
    assertThat(messagesIn(CHINESE).get("stopping")).isEqualTo("正在停止。");
  }

  @Test
  void fallsBackToEnglishForALanguageThatDoesNotShip() {
    assertThat(messagesIn(Locale.JAPANESE).get("stopping")).isEqualTo("Stopping.");
  }

  @Test
  void fillsInArguments() {
    assertThat(messagesIn(Locale.ENGLISH).get("unknown-command", "/nope"))
        .isEqualTo("Unknown command: /nope");
    assertThat(messagesIn(CHINESE).get("unknown-command", "/nope")).contains("/nope");
  }

  @Test
  void answersWithTheKeyRatherThanThrowingOnOneItDoesNotHave() {
    assertThat(messagesIn(Locale.ENGLISH).get("no-such-key")).isEqualTo("no-such-key");
  }

  @Test
  void everyTranslationCoversEveryKey() throws IOException {
    final var english = keysOf("messages.properties");
    final var chinese = keysOf("messages_zh_CN.properties");

    assertThat(chinese).containsExactlyInAnyOrderElementsOf(english);
  }

  private static Set<String> keysOf(final String bundle) throws IOException {
    final var properties = new Properties();
    try (var stream = CliMessagesTest.class.getClassLoader().getResourceAsStream(bundle)) {
      assertThat(stream).as(bundle).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties.stringPropertyNames();
  }
}
