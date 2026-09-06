package me.kezhenxu94.springagent.tools.shell.docker.i18n;

import me.kezhenxu94.springagent.core.i18n.AbstractToolTranslationsCompleteTest;

/** {@inheritDoc} */
class DockerShellToolTranslationsCompleteTest extends AbstractToolTranslationsCompleteTest {

  @Override
  protected String basePackage() {
    return DockerShellI18n.PACKAGE;
  }

  @Override
  protected String bundleBase() {
    return DockerShellI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return DockerShellI18n.TOOLS_LOCATION;
  }
}
