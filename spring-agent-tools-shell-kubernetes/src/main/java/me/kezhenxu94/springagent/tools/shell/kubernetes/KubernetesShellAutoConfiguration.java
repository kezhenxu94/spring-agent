package me.kezhenxu94.springagent.tools.shell.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import me.kezhenxu94.springagent.core.config.ConditionalOnShellBackend;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.credentials.CredentialTools;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Runs the agent's {@code Bash} tool in a per-user sandbox Pod.
 *
 * <p>This class's fully qualified name is the marker {@code ShellBackendResolver} looks for, so
 * renaming or moving it means updating that too.
 */
@AutoConfiguration
@ImportRuntimeHints(KubernetesShellRuntimeHints.class)
@ConditionalOnShellBackend(Type.KUBERNETES)
@EnableConfigurationProperties(KubernetesShellProperties.class)
public class KubernetesShellAutoConfiguration {

  /**
   * This backend's tool translations.
   *
   * <p>Its own rather than core's because every shell backend declares tools of the same names —
   * {@code Bash}, {@code BashOutput}, {@code KillShell} — with genuinely different text, and the
   * name is what a translation is keyed by. Core cannot hold all of them under one key, and holding
   * one of them would serve that backend's wording to whichever backend is actually running. This
   * bean exists only while this backend does, the whole class being conditional on it.
   */
  @Bean
  ToolTexts kubernetesShellToolTexts(final SpringAgentProperties properties) {
    return new ModuleToolTexts(
        "shell-kubernetes/tools", "shell-kubernetes/prompts/tools/", properties.locale());
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean
  @ConditionalOnMissingBean
  UserPodManager userPodManager(
      final KubernetesClient kubernetesClient,
      final KubernetesShellProperties properties,
      final SpringAgentProperties appConfiguration) {
    return new UserPodManager(kubernetesClient, properties, appConfiguration);
  }

  @Bean
  @ConditionalOnMissingBean
  KubernetesShellTools kubernetesShellTools(
      final KubernetesClient kubernetesClient,
      final UserPodManager userPodManager,
      final KubernetesShellProperties properties) {
    return new KubernetesShellTools(kubernetesClient, userPodManager, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  ShellCredentialStore shellCredentialStore(
      final KubernetesClient kubernetesClient, final UserPodManager userPodManager) {
    return new KubernetesSecretCredentialStore(kubernetesClient, userPodManager);
  }

  @Bean
  @ConditionalOnMissingBean
  CredentialTools credentialTools(final ShellCredentialStore store) {
    return new CredentialTools(store, "RestartShellPod");
  }
}
