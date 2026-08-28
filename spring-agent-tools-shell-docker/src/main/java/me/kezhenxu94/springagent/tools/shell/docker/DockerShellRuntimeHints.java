package me.kezhenxu94.springagent.tools.shell.docker;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * This backend's tool translations, whose locale is only known when the binary runs.
 *
 * <p>Resource patterns rather than a resource bundle: {@code ModuleToolTexts} reads the properties
 * as a resource on purpose, so as not to go through a {@code ResourceBundle} and have it consult
 * the host's locale ahead of the base file.
 */
public class DockerShellRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints.resources().registerPattern("shell-docker/prompts/tools/*.md");
    hints.resources().registerPattern("shell-docker/tools.properties");
    hints.resources().registerPattern("shell-docker/tools_*.properties");
  }
}
