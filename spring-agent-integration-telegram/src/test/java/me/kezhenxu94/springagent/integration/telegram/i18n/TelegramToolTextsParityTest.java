package me.kezhenxu94.springagent.integration.telegram.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractToolTextsParityTest;

/** {@inheritDoc} */
class TelegramToolTextsParityTest extends AbstractToolTextsParityTest {

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

  @Override
  protected List<String> knownTools() {
    return List.of("TelegramSendMessage", "TelegramGetChat");
  }
}
