package me.kezhenxu94.springagent.integration.telegram.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import me.kezhenxu94.springagent.integration.telegram.tools.TelegramTools;

/** {@inheritDoc} */
class TelegramToolsLocalizedEndToEndTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return TelegramI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return TelegramI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<Object> tools() {
    return List.of(new TelegramTools(null, null, null, null));
  }
}
