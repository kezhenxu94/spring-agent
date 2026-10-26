package me.kezhenxu94.springagent.integration.telegram.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractEveryToolTranslatedTest;

/** {@inheritDoc} */
class TelegramEveryToolTranslatedTest extends AbstractEveryToolTranslatedTest {

  @Override
  protected String basePackage() {
    return TelegramI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return TelegramI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return TelegramI18n.TOOLS_LOCATION;
  }
}
