package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link AgentTool} has to be honoured on a {@code @Bean} factory method as well as on a class,
 * since a tool whose type comes from a library cannot carry the annotation itself, and the run's
 * scenario has the last word on which of them it is offered.
 */
class AgentToolsProviderScenarioTest {

  static class LibraryTool {}

  static class OwnedTool {}

  @AgentTool
  static class AnnotatedOwnedTool extends OwnedTool {}

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
    OwnedTool plainBean() {
      return new OwnedTool();
    }
  }

  @Test
  @DisplayName("collects @AgentTool from both a @Bean method and a class, and nothing else")
  void collectsAnnotatedBeansAndFactoryMethods() {
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      assertThat(provider(context).resolveScenarioTools(BuiltInScenarios.CHAT))
          .extracting(Object::getClass)
          .containsExactlyInAnyOrder(LibraryTool.class, AnnotatedOwnedTool.class);
    }
  }

  @Test
  @DisplayName("a scenario keeps out a tool it does not want, its own or one shipped here")
  void aScenarioDecidesWhatItIsOffered() {
    // What an SDK consumer can do: their own scenario, ruling on a tool that knows nothing about
    // it.
    final var ownScenario =
        new AgentScenario() {
          @Override
          public boolean conversationMemory() {
            return false;
          }

          @Override
          public boolean offers(final Object tool) {
            return !(tool instanceof LibraryTool);
          }
        };

    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      assertThat(provider(context).resolveScenarioTools(ownScenario))
          .extracting(Object::getClass)
          .containsExactly(AnnotatedOwnedTool.class);
    }
  }

  @Test
  @DisplayName("a scheduled run is not offered the tool that schedules runs")
  void aScheduledRunCannotScheduleMore() {
    final var scheduledTaskTool =
        new ScheduledTaskTool(mock(ScheduledTaskRepo.class), mock(ScheduledTaskService.class));

    assertThat(BuiltInScenarios.SCHEDULED_TASK.offers(scheduledTaskTool)).isFalse();
    assertThat(BuiltInScenarios.CHAT.offers(scheduledTaskTool)).isTrue();
  }

  private static AgentToolsProvider provider(final AnnotationConfigApplicationContext context) {
    return new AgentToolsProvider(
        mock(UserWorkspaceFactory.class),
        mock(McpServerConfigRepo.class),
        mock(McpClientFactory.class),
        context,
        mock(SpringAgentProperties.class),
        mock(org.springframework.beans.factory.ObjectProvider.class));
  }
}
