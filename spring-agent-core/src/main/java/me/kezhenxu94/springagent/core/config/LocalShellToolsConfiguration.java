package me.kezhenxu94.springagent.core.config;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.tools.AgentTool;
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
