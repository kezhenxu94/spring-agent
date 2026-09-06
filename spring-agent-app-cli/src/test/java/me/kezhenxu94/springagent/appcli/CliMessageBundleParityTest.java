package me.kezhenxu94.springagent.appcli;

import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class CliMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return "messages";
  }
}
