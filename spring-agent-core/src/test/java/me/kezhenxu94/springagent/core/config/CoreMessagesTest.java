package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These read back to the model rather than to a person, so a key that resolves to nothing would not
 * look broken — it would quietly become an instruction the model cannot follow.
 */
class CoreMessagesTest {

  private static final Locale CHINESE = Locale.of("zh", "CN");

  static CoreMessages messagesIn(final Locale locale) {
    return new CoreMessages(new SpringAgentProperties(null, null, locale));
  }

  @Test
  void speaksTheLocaleItWasGiven() {
    assertThat(messagesIn(Locale.ENGLISH).get("questions-asked-heading"))
        .isEqualTo("I have already put these questions to the user, and they have been presented:");
    assertThat(messagesIn(CHINESE).get("questions-asked-heading")).startsWith("我已经向用户提出了以下问题");
  }

  @Test
  void fallsBackToEnglishForALanguageThatDoesNotShip() {
    assertThat(messagesIn(Locale.JAPANESE).get("questions-asked-heading"))
        .startsWith("I have already put these questions");
  }

  @Test
  @DisplayName("an unconfigured locale is the host's, not a failure")
  void defaultsToTheHostLocale() {
    assertThat(messagesIn(null).locale()).isEqualTo(Locale.getDefault());
  }

  @Test
  void fillsInArguments() {
    assertThat(messagesIn(Locale.ENGLISH).get("questions-asked-item", "Auth method", "Which one?"))
        .isEqualTo("- Auth method: Which one?");
  }

  @Test
  void answersWithTheKeyRatherThanThrowingOnOneItDoesNotHave() {
    assertThat(messagesIn(Locale.ENGLISH).get("no-such-key")).isEqualTo("no-such-key");
  }

  @Test
  void everyTranslationCoversEveryKey() throws IOException {
    final var english = keysOf("core/messages.properties");
    final var chinese = keysOf("core/messages_zh_CN.properties");

    assertThat(chinese).containsExactlyInAnyOrderElementsOf(english);
  }

  private static Set<String> keysOf(final String bundle) throws IOException {
    final var properties = new Properties();
    try (var stream = CoreMessagesTest.class.getClassLoader().getResourceAsStream(bundle)) {
      assertThat(stream).as(bundle).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties.stringPropertyNames();
  }
}
