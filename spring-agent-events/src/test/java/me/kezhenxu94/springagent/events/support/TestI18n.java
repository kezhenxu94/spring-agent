package me.kezhenxu94.springagent.events.support;

import java.util.Locale;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.events.config.EventsMessages;
import me.kezhenxu94.springagent.events.situation.TriagePrompts;

/**
 * This module's translated text, in a language the test names.
 *
 * <p>Always with a locale stated, which is the point of the helper. Both of these fall back to the
 * workspace's locale, and a test that left it unset would read whatever the machine running it is
 * configured for — so a suite asserting English text would pass or fail depending on whose laptop
 * it ran on.
 */
public final class TestI18n {

  private TestI18n() {}

  public static EventsMessages messages(final Locale locale) {
    return new EventsMessages(properties(locale));
  }

  public static EventsMessages english() {
    return messages(Locale.ENGLISH);
  }

  public static TriagePrompts prompts(final Locale locale) {
    return new TriagePrompts(properties(locale));
  }

  private static SpringAgentProperties properties(final Locale locale) {
    return new SpringAgentProperties(null, null, locale, null, null);
  }
}
