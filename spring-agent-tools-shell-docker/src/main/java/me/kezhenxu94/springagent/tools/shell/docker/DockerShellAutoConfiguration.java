package me.kezhenxu94.springagent.tools.shell.docker;

import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.ConditionalOnShellBackend;
import me.kezhenxu94.springagent.core.config.ShellToolsProperties.Type;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.credentials.CredentialTools;
import me.kezhenxu94.springagent.core.tools.credentials.DatabaseCredentialStore;
import me.kezhenxu94.springagent.core.tools.credentials.ShellCredentialStore;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

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
@ImportRuntimeHints(DockerShellRuntimeHints.class)
@ConditionalOnShellBackend(Type.DOCKER)
@EnableConfigurationProperties(DockerShellProperties.class)
public class DockerShellAutoConfiguration {

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
  ToolTexts dockerShellToolTexts(final SpringAgentProperties properties) {
    return new ModuleToolTexts(
        "shell-docker/tools", "shell-docker/prompts/tools/", properties.locale());
  }

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
      final Admins admins,
      final ShellCredentialStore credentialStore) {
    return new UserContainerManager(properties, storageProperties, admins, credentialStore);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  DockerShellTools dockerShellTools(
      final UserContainerManager userContainerManager, final DockerShellProperties properties) {
    return new DockerShellTools(userContainerManager, properties);
  }
}
