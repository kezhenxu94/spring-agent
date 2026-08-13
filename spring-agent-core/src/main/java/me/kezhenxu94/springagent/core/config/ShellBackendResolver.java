package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Decides which shell is in play, for {@link ConditionalOnShellBackend}.
 *
 * <p>The classpath never decides: an unset {@code app.ai.tools.shell.type} means no shell. It is
 * consulted only to explain a misconfiguration, so that naming a shell whose module is missing says
 * which artifact to add. Resolved by name, so this module keeps no compile dependency on the module
 * it names.
 */
final class ShellBackendResolver {

  static final String TYPE_PROPERTY = "app.ai.tools.shell.type";

  static final String KUBERNETES_ARTIFACT = "spring-agent-tools-shell-kubernetes";

  private static final String KUBERNETES_MODULE =
      "me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellAutoConfiguration";

  private ShellBackendResolver() {}

  /** Whether the module implementing {@code type} is present. Only KUBERNETES needs one. */
  static boolean present(final Type type, final ClassLoader classLoader) {
    return type != Type.KUBERNETES || ClassUtils.isPresent(KUBERNETES_MODULE, classLoader);
  }

  static Type resolve(final Environment environment) {
    final var value = environment.getProperty(TYPE_PROPERTY);
    if (value == null || value.isBlank()) {
      return Type.NONE;
    }
    return Type.valueOf(value.trim().toUpperCase());
  }
}
