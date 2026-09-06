package me.kezhenxu94.springagent.integration.slack.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractToolTextsParityTest;

/** {@inheritDoc} */
class SlackToolTextsParityTest extends AbstractToolTextsParityTest {

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

  @Override
  protected List<String> knownTools() {
    return List.of("SlackSendMessage", "SlackListChannels");
  }
}
