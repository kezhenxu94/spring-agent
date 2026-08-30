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
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * These read back to the model rather than to a person, so a key that resolves to nothing would not
 * look broken — it would quietly become an instruction the model cannot follow.
 */
class CoreMessagesTest {

  private static final Locale CHINESE = Locale.of("zh", "CN");

  static CoreMessages messagesIn(final Locale locale) {
    return messagesIn(locale, false);
  }

  /** An application's message source, configured the way the shipped applications configure it. */
  static CoreMessages messagesIn(final Locale locale, final boolean fallbackToSystemLocale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(fallbackToSystemLocale);
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale, null));
  }

  @Test
  void speaksTheLocaleItWasGiven() {
    assertThat(messagesIn(Locale.ENGLISH).get("question-already-asked"))
        .startsWith("ALREADY ASKED AND STILL UNANSWERED.");
    assertThat(messagesIn(CHINESE).get("question-already-asked")).startsWith("已经提问且仍未得到回答");
  }

  @Test
  void fallsBackToEnglishForALanguageThatDoesNotShip() {
    assertThat(messagesIn(Locale.JAPANESE).get("question-already-asked"))
        .startsWith("ALREADY ASKED");
  }

  @Test
  @DisplayName("an unconfigured locale is the host's, not a failure")
  void defaultsToTheHostLocale() {
    assertThat(messagesIn(null).locale()).isEqualTo(Locale.getDefault());
  }

  @Test
  @DisplayName("the command line's fallback lands on the host's language, not on English")
  void fallsBackToTheSystemLocaleWhenAskedTo() {
    // Only observable with a host that has a bundle of its own, which the CI machine need not
    // have; asserting on the requested-locale case would pass either way and prove nothing.
    final var host = Locale.getDefault();
    try {
      Locale.setDefault(CHINESE);
      assertThat(messagesIn(Locale.JAPANESE, true).get("question-already-asked"))
          .startsWith("已经提问且仍未得到回答");
      assertThat(messagesIn(Locale.JAPANESE, false).get("question-already-asked"))
          .startsWith("ALREADY ASKED");
    } finally {
      Locale.setDefault(host);
    }
  }

  @Test
  @DisplayName("a key the bundles do not have, or cannot be reached, answers with the key itself")
  void answersWithTheKeyRatherThanThrowingOnOneItDoesNotHave() {
    assertThat(messagesIn(Locale.ENGLISH).get("no-such-key")).isEqualTo("no-such-key");

    final var withoutTheBundle = new ResourceBundleMessageSource();
    withoutTheBundle.setBasename("some/other/bundle");
    final var unreachable =
        new CoreMessages(
            withoutTheBundle, new SpringAgentProperties(null, null, Locale.ENGLISH, null));

    assertThat(unreachable.get("question-already-asked")).isEqualTo("question-already-asked");
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
