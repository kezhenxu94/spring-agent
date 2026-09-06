package me.kezhenxu94.springagent.integration.slack.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import me.kezhenxu94.springagent.integration.slack.tools.SlackTools;

/** {@inheritDoc} */
class SlackToolsLocalizedEndToEndTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return SlackI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return SlackI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<Object> tools() {
    return List.of(new SlackTools(null, null, null, null, null, null));
  }
}
