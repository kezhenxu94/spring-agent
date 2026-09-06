package me.kezhenxu94.springagent.tools.shell.kubernetes.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellTools;

/** {@inheritDoc} */
class KubernetesShellToolsLocalizedEndToEndTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return KubernetesShellI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return KubernetesShellI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<Object> tools() {
    return List.of(new KubernetesShellTools(null, null, null, null));
  }
}
