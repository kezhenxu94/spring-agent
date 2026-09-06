package me.kezhenxu94.springagent.integration.slack.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractToolTranslationsCompleteTest;

/** {@inheritDoc} */
class SlackToolTranslationsCompleteTest extends AbstractToolTranslationsCompleteTest {

  @Override
  protected String basePackage() {
    return SlackI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return SlackI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return SlackI18n.TOOLS_LOCATION;
  }
}
