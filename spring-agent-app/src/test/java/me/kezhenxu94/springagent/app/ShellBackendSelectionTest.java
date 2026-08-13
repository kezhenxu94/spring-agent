package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.client.KubernetesClient;
import me.kezhenxu94.springagent.core.config.LocalShellToolsConfiguration;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.tools.shell.docker.DockerShellAutoConfiguration;
import me.kezhenxu94.springagent.tools.shell.docker.DockerShellTools;
import me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellAutoConfiguration;
import me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code app.ai.tools.shell.type} across all of its outcomes. This application carries every shell,
 * so it is the one place every branch of {@code ConditionalOnShellBackend} is reachable from a
 * single classpath.
 */
@ExtendWith(OutputCaptureExtension.class)
class ShellBackendSelectionTest {

  /** Enough for the Docker shell to wire; the value is never decrypted here. */
  private static final String ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  LocalShellToolsConfiguration.class,
                  KubernetesShellAutoConfiguration.class,
                  DockerShellAutoConfiguration.class))
          .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class))
          .withBean(StorageProperties.class, () -> mock(StorageProperties.class))
          .withBean(ShellCredentialRepo.class, () -> mock(ShellCredentialRepo.class))
          .withBean(SpringAgentProperties.class, () -> mock(SpringAgentProperties.class));

  @Test
  @DisplayName("no property means no shell at all, even with every module on the classpath")
  void defaultsToNoShell() {
    runner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ShellTools.class)
                .doesNotHaveBean(KubernetesShellTools.class)
                .doesNotHaveBean(DockerShellTools.class));
  }

  @Test
  @DisplayName("type=none is the same as saying nothing")
  void noneMeansNoShell() {
    runner
        .withPropertyValues("app.ai.tools.shell.type=none")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(ShellTools.class)
                    .doesNotHaveBean(KubernetesShellTools.class)
                    .doesNotHaveBean(DockerShellTools.class));
  }

  @Test
  @DisplayName(
      "type=local wires Spring AI's in-process ShellTools and warns that it is unsandboxed")
  void localWiresShellTools(final CapturedOutput output) {
    runner
        .withPropertyValues("app.ai.tools.shell.type=local")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(ShellTools.class)
                    .doesNotHaveBean(KubernetesShellTools.class)
                    .doesNotHaveBean(DockerShellTools.class));

    assertThat(output).contains("app.ai.tools.shell.type=local").contains("There is no sandbox");
  }

  @Test
  @DisplayName("type=kubernetes wires the sandbox pod tools and no local shell")
  void kubernetesWiresSandboxTools() {
    runner
        .withPropertyValues(
            "app.ai.tools.shell.type=kubernetes",
            "app.ai.tools.shell.kubernetes.image=test-image:latest")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(KubernetesShellTools.class)
                    .doesNotHaveBean(ShellTools.class)
                    .doesNotHaveBean(DockerShellTools.class));
  }

  @Test
  @DisplayName("type=kubernetes without the image is a startup failure naming the property")
  void kubernetesRequiresAnImage() {
    runner
        .withPropertyValues("app.ai.tools.shell.type=kubernetes")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.ai.tools.shell.kubernetes.image"));
  }

  @Test
  @DisplayName("type=kubernetes without the module registers nothing rather than failing obscurely")
  void kubernetesWithoutTheModuleBacksOff() {
    runner
        .withClassLoader(new FilteredClassLoader(KubernetesShellAutoConfiguration.class))
        .withPropertyValues(
            "app.ai.tools.shell.type=kubernetes",
            "app.ai.tools.shell.kubernetes.image=test-image:latest")
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ShellTools.class));
  }

  @Test
  @DisplayName("type=docker wires the sandbox container tools and no other shell")
  void dockerWiresSandboxTools() {
    runner
        .withPropertyValues(
            "app.ai.tools.shell.type=docker",
            "app.ai.tools.shell.docker.image=test-image:latest",
            "app.ai.tools.shell.docker.credentials.encryption-key=" + ENCRYPTION_KEY)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(DockerShellTools.class)
                    .doesNotHaveBean(ShellTools.class)
                    .doesNotHaveBean(KubernetesShellTools.class));
  }

  @Test
  @DisplayName("type=docker without the image is a startup failure naming the property")
  void dockerRequiresAnImage() {
    runner
        .withPropertyValues(
            "app.ai.tools.shell.type=docker",
            "app.ai.tools.shell.docker.credentials.encryption-key=" + ENCRYPTION_KEY)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.ai.tools.shell.docker.image"));
  }

  @Test
  @DisplayName(
      "type=docker without an encryption key fails rather than storing secrets in the clear")
  void dockerRequiresAnEncryptionKey() {
    runner
        .withPropertyValues(
            "app.ai.tools.shell.type=docker", "app.ai.tools.shell.docker.image=test-image:latest")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.ai.tools.shell.docker.credentials.encryption-key"));
  }

  @Test
  @DisplayName("type=docker without the module registers nothing rather than failing obscurely")
  void dockerWithoutTheModuleBacksOff() {
    runner
        .withClassLoader(new FilteredClassLoader(DockerShellAutoConfiguration.class))
        .withPropertyValues(
            "app.ai.tools.shell.type=docker",
            "app.ai.tools.shell.docker.image=test-image:latest",
            "app.ai.tools.shell.docker.credentials.encryption-key=" + ENCRYPTION_KEY)
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ShellTools.class));
  }
}
