package me.kezhenxu94.springagent.events.i18n;

import java.util.Set;
import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class EventsMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return EventsI18n.MESSAGES;
  }

  @Override
  protected Set<String> sameInEveryLanguage() {
    // A dash, standing in for a field the observation did not carry. There is nothing to translate.
    return Set.of("brief-unknown");
  }
}
