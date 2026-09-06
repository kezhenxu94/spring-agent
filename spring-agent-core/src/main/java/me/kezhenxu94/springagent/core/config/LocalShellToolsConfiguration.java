package me.kezhenxu94.springagent.core.config;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI's own {@code ShellTools}, running commands in this process. Needs no cluster, image or
 * volume, and provides no isolation whatsoever.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnShellBackend(Type.LOCAL)
public class LocalShellToolsConfiguration {

  /**
   * This backend's tool translations.
   *
   * <p>Its own bundle rather than core's, for the reason the two sandboxed backends have one: all
   * three declare tools of the same names — {@code Bash}, {@code BashOutput}, {@code KillShell} —
   * with genuinely different text, and the name is what a translation is keyed by. Held in core
   * only because the backend is: the bean exists only while this backend does, the whole class
   * being conditional on it, so no two of them are ever asked about {@code Bash} at once.
   *
   * <p>What the library's tools <em>answer</em> with cannot be translated from here — that text is
   * built inside {@code ShellTools} — which is one more reason this backend is for a laptop and the
   * sandboxed ones are for a deployment.
   */
  @Bean
  ToolTexts localShellToolTexts(final SpringAgentProperties properties) {
    return new ModuleToolTexts(
        "shell-local/tools", "shell-local/prompts/tools/", properties.locale());
  }

  // @AgentTool on the factory method: ShellTools is a third-party type and cannot carry it.
  // Subclassing to attach it does not work either — Spring AI's MethodToolCallbackProvider scans
  // with ReflectionUtils.getDeclaredMethods, which does not see inherited @Tool methods.
  @Bean
  @AgentTool
  @ConditionalOnMissingBean
  ShellTools localShellTools() {
    log.warn(
        """

        ################################################################################
        app.ai.tools.shell.type=local — the agent can run any command on THIS machine.

        There is no sandbox. Commands the model writes execute as the operating system
        user this application runs as, with its filesystem, its environment variables,
        its cloud credentials and its network reachability. A prompt injected through
        any content the agent reads can therefore delete data, exfiltrate secrets or
        reach internal services, and nothing here will stop it.

        There is also no isolation between users: ShellTools keeps its background
        shells in a static map and takes no user identity, so one user's chat can read
        and kill another user's commands, and every user shares one working directory.

        Use this for local development only. For anything with real users or real
        credentials, set app.ai.tools.shell.type=kubernetes and add
        spring-agent-tools-shell-kubernetes, which gives each user a disposable Pod —
        or, on a single host with no cluster, app.ai.tools.shell.type=docker with
        spring-agent-tools-shell-docker, which gives each user a container.
        ################################################################################
        """);
    return ShellTools.builder().build();
  }
}
