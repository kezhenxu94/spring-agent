package me.kezhenxu94.springagent.tools.shell.docker.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import me.kezhenxu94.springagent.tools.shell.docker.DockerShellTools;

/** {@inheritDoc} */
class DockerShellToolsLocalizedEndToEndTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return DockerShellI18n.TOOLS_BUNDLE;
  }

  @Override
  protected String promptDirectory() {
    return DockerShellI18n.TOOLS_LOCATION;
  }

  @Override
  protected List<Object> tools() {
    return List.of(new DockerShellTools(null, null, null));
  }
}
