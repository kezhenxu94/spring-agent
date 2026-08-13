package me.kezhenxu94.springagent.tools.shell.docker;

import me.kezhenxu94.springagent.core.config.ConditionalOnShellBackend;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.credentials.CredentialTools;
import me.kezhenxu94.springagent.core.tools.credentials.DatabaseCredentialStore;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Runs the agent's {@code Bash} tool in a per-user sandbox container on the local Docker daemon.
 *
 * <p>This class's fully qualified name is the marker {@code ShellBackendResolver} looks for, so
 * renaming or moving it means updating that too.
 *
 * <p>There is no credential code in this module. Docker has no counterpart to a Kubernetes Secret,
 * so the store here is the database-backed one from core, and the tools over it are core's too.
 */
@AutoConfiguration
@ConditionalOnShellBackend(Type.DOCKER)
@EnableConfigurationProperties(DockerShellProperties.class)
public class DockerShellAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ShellCredentialStore shellCredentialStore(
      final ShellCredentialRepo repo, final DockerShellProperties properties) {
    return new DatabaseCredentialStore(repo, properties.credentials().encryptionKey());
  }

  @Bean
  @ConditionalOnMissingBean
  CredentialTools credentialTools(final ShellCredentialStore store) {
    return new CredentialTools(store, "RestartShellContainer");
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  UserContainerManager userContainerManager(
      final DockerShellProperties properties,
      final StorageProperties storageProperties,
      final SpringAgentProperties appConfiguration,
      final ShellCredentialStore credentialStore) {
    return new UserContainerManager(
        properties, storageProperties, appConfiguration, credentialStore);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DockerShellTools dockerShellTools(
      final UserContainerManager userContainerManager, final DockerShellProperties properties) {
    return new DockerShellTools(userContainerManager, properties);
  }
}
