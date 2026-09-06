package me.kezhenxu94.springagent.tools.shell.docker.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractToolTextsParityTest;

/** {@inheritDoc} */
class DockerShellToolTextsParityTest extends AbstractToolTextsParityTest {

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

  @Override
  protected List<String> knownTools() {
    return List.of("Bash", "BashOutput", "KillShell", "RestartShellContainer");
  }
}
