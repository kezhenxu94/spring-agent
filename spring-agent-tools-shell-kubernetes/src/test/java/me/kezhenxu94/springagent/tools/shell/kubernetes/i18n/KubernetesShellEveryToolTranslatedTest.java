package me.kezhenxu94.springagent.tools.shell.kubernetes.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractEveryToolTranslatedTest;

/** {@inheritDoc} */
class KubernetesShellEveryToolTranslatedTest extends AbstractEveryToolTranslatedTest {

  @Override
  protected String basePackage() {
    return KubernetesShellI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return KubernetesShellI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return KubernetesShellI18n.TOOLS_LOCATION;
  }
}
