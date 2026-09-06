package me.kezhenxu94.springagent.core.support;

import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Core's own text, in a language the test names, read out of the bundle core really ships.
 *
 * <p>Always with a locale stated, which is the point of the helper: {@link CoreMessages} falls back
 * to the workspace's locale and thence to the host's, so a test that left it unset would read
 * whatever the machine running it is configured for — and a suite asserting English would pass or
 * fail depending on whose laptop it ran on.
 *
 * <p>A real {@link ResourceBundleMessageSource} rather than a stub, configured as the shipped
 * applications configure theirs. A stub answering every key would let a tool go on returning text
 * that has no entry in the bundle at all, which is exactly the failure these tests exist to catch.
 */
public final class TestI18n {

  private TestI18n() {}

  public static CoreMessages messages(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    // The shipped applications set this false, so that a locale with no bundle lands on English
    // rather than on whatever the host happens to speak.
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale, null, null));
  }

  public static CoreMessages english() {
    return messages(Locale.ENGLISH);
  }

  public static CoreMessages chinese() {
    return messages(Locale.SIMPLIFIED_CHINESE);
  }
}
