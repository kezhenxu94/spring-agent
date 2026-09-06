package me.kezhenxu94.springagent.tools.shell.docker;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * This backend's tool translations, whose locale is only known when the binary runs.
 *
 * <p>And what its tools answer with, which is a resource bundle proper: {@code ModuleMessages}
 * reads it through a {@code ResourceBundleMessageSource}, with the host-locale fallback turned off,
 * so the bundle has to be registered as one.
 *
 * <p>Resource patterns for the rest rather than a resource bundle: {@code ModuleToolTexts} reads
 * those properties as a resource on purpose, so as not to go through a {@code ResourceBundle} and
 * have it consult the host's locale ahead of the base file.
 */
public class DockerShellRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints.resources().registerResourceBundle("shell-docker.messages");
    hints.resources().registerPattern("shell-docker/prompts/tools/*.md");
    hints.resources().registerPattern("shell-docker/tools.properties");
    hints.resources().registerPattern("shell-docker/tools_*.properties");
  }
}
