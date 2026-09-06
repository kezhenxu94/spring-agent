package me.kezhenxu94.springagent.tools.shell.kubernetes.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractToolTextsParityTest;

/** {@inheritDoc} */
class KubernetesShellToolTextsParityTest extends AbstractToolTextsParityTest {

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

  @Override
  protected List<String> knownTools() {
    return List.of("Bash", "BashOutput", "KillShell", "RestartShellPod");
  }
}
