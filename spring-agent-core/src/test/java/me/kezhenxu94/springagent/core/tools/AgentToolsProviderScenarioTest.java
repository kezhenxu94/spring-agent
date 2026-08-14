package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.agent.AgentScenario;
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

  @AgentTool(scenario = AgentScenario.SCHEDULED_TASK)
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

      assertThat(provider.resolveScenarioTools(AgentScenario.CHAT))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(LibraryTool.class, AnnotatedOwnedTool.class);

      assertThat(provider.resolveScenarioTools(AgentScenario.SCHEDULED_TASK))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(
              LibraryTool.class, AnnotatedOwnedTool.class, ScheduledOnlyTool.class);
    }
  }
}
