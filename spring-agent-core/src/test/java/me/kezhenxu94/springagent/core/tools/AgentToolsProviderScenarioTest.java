package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link AgentTool} has to be honoured on a {@code @Bean} factory method as well as on a class,
 * since a tool whose type comes from a library cannot carry the annotation itself.
 */
class AgentToolsProviderScenarioTest {

  static class LibraryTool {}

  static class OwnedTool {}

  @AgentTool
  static class AnnotatedOwnedTool extends OwnedTool {}

  @AgentTool(scenario = BuiltInScenarios.SCHEDULED_TASK)
  static class ScheduledOnlyTool {}

  @Configuration(proxyBeanMethods = false)
  static class Tools {

    @Bean
    @AgentTool
    LibraryTool libraryTool() {
      return new LibraryTool();
    }

    @Bean
    AnnotatedOwnedTool annotatedOwnedTool() {
      return new AnnotatedOwnedTool();
    }

    @Bean
    ScheduledOnlyTool scheduledOnlyTool() {
      return new ScheduledOnlyTool();
    }

    @Bean
    OwnedTool plainBean() {
      return new OwnedTool();
    }
  }

  @Test
  @DisplayName("collects @AgentTool from both a @Bean method and a class, filtered by scenario")
  void collectsAnnotatedBeansAndFactoryMethods() {
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      final var provider =
          new AgentToolsProvider(
              mock(UserWorkspaceFactory.class),
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              mock(SpringAgentProperties.class));

      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(LibraryTool.class, AnnotatedOwnedTool.class);

      assertThat(provider.resolveScenarioTools(BuiltInScenarios.SCHEDULED_TASK))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(
              LibraryTool.class, AnnotatedOwnedTool.class, ScheduledOnlyTool.class);
    }
  }

  @Test
  @DisplayName("a scenario of a consumer's own is offered the tools declared for every run")
  void collectsForACustomScenario() {
    // What an SDK consumer can do: @AgentTool cannot name a scenario outside the enum, so a run in
    // their own scenario gets exactly the ALL tools.
    final AgentScenario ownScenario = () -> false;

    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      final var provider =
          new AgentToolsProvider(
              mock(UserWorkspaceFactory.class),
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              mock(SpringAgentProperties.class));

      assertThat(provider.resolveScenarioTools(ownScenario))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(LibraryTool.class, AnnotatedOwnedTool.class);
    }
  }
}
