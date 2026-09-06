package me.kezhenxu94.springagent.tools.shell.kubernetes.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractMessageBundleParityTest;

/** {@inheritDoc} */
class KubernetesShellMessageBundleParityTest extends AbstractMessageBundleParityTest {

  @Override
  protected String bundlePath() {
    return KubernetesShellI18n.MESSAGES;
  }
}
