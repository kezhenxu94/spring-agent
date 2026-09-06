package me.kezhenxu94.springagent.tools.shell.docker.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class DockerShellMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return DockerShellI18n.MESSAGES;
  }
}
