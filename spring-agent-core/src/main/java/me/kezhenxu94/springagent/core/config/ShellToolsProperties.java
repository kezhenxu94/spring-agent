package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which shell the agent's {@code Bash} tool runs commands through, if any.
 *
 * <p>Unlike {@code app.persistence.type}, this is not read off the classpath: adding a jar must not
 * be enough to grant a language model arbitrary command execution, so a deployment that wants a
 * shell says so.
 *
 * <p>Settings belonging to a particular shell are bound by the module implementing it. Only the
 * selector is here, because only this module is guaranteed to be present.
 *
 * @param type which shell, if any. {@link Type#NONE} by default.
 */
@ConfigurationProperties(prefix = "app.ai.tools.shell")
public record ShellToolsProperties(Type type) {

  public ShellToolsProperties {
    if (type == null) {
      type = Type.NONE;
    }
  }

  public enum Type {
    /** No shell. The agent gets no {@code Bash}, {@code BashOutput} or {@code KillShell} tool. */
    NONE,
    /**
     * Spring AI's own {@code ShellTools}, running commands as the application's own OS user, with
     * no sandbox of any kind.
     */
    LOCAL,
    /**
     * A per-user sandbox Pod. Requires {@code spring-agent-tools-shell-kubernetes} on the classpath
     * and {@code app.ai.tools.shell.kubernetes.image} to be set.
     */
    KUBERNETES
  }
}
