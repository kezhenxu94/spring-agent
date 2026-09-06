package me.kezhenxu94.springagent.events.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractToolTranslationsCompleteTest;

/** {@inheritDoc} */
class EventsToolTranslationsCompleteTest extends AbstractToolTranslationsCompleteTest {

  @Override
  protected String basePackage() {
    return EventsI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return EventsI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return EventsI18n.TOOLS_LOCATION;
  }
}
