package me.kezhenxu94.springagent.tools.shell.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import me.kezhenxu94.springagent.core.config.ConditionalOnShellBackend;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Runs the agent's {@code Bash} tool in a per-user sandbox Pod.
 *
 * <p>This class's fully qualified name is the marker {@code ShellBackendResolver} looks for, so
 * renaming or moving it means updating that too.
 */
@AutoConfiguration
@ConditionalOnShellBackend(Type.KUBERNETES)
@EnableConfigurationProperties(KubernetesShellProperties.class)
public class KubernetesShellAutoConfiguration {

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
      final StorageProperties storageProperties,
      final SpringAgentProperties appConfiguration) {
    return new UserPodManager(kubernetesClient, properties, storageProperties, appConfiguration);
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
  CredentialTools credentialTools(
      final KubernetesClient kubernetesClient, final UserPodManager userPodManager) {
    return new CredentialTools(kubernetesClient, userPodManager);
  }
}
