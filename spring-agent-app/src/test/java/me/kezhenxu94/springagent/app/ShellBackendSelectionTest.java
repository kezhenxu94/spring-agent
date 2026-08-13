package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.fabric8.kubernetes.client.KubernetesClient;
import me.kezhenxu94.springagent.core.config.LocalShellToolsConfiguration;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
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
 * {@code app.ai.tools.shell.type} across all of its outcomes. This application carries both shells,
 * so it is the one place every branch of {@code ConditionalOnShellBackend} is reachable from a
 * single classpath.
 */
@ExtendWith(OutputCaptureExtension.class)
class ShellBackendSelectionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  LocalShellToolsConfiguration.class, KubernetesShellAutoConfiguration.class))
          .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class))
          .withBean(StorageProperties.class, () -> mock(StorageProperties.class))
          .withBean(SpringAgentProperties.class, () -> mock(SpringAgentProperties.class));

  @Test
  @DisplayName("no property means no shell at all, even with both modules on the classpath")
  void defaultsToNoShell() {
    runner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ShellTools.class)
                .doesNotHaveBean(KubernetesShellTools.class));
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
                    .doesNotHaveBean(KubernetesShellTools.class));
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
                    .doesNotHaveBean(KubernetesShellTools.class));

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
                    .doesNotHaveBean(ShellTools.class));
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
}
