package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
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

  /** Stands in for a real one — {@code PlaybookTools} — which core cannot see from here. */
  @AgentTool
  static class AnAdminTool implements AdminTool {}

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

    @Bean
    AnAdminTool anAdminTool() {
      return new AnAdminTool();
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
  @DisplayName("an admin tool needs both an eligible scenario and an administrator")
  void anAdminToolNeedsBothHalves() {
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      final var provider = provider(context, "ou_admin");

      // Both halves: a chat run, which is the only built-in scenario that says adminTools(), and a
      // person named in app.ai.admins.
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, "ou_admin"))
          .hasAtLeastOneElementOfType(AnAdminTool.class);

      // The person, but not the kind of run. This is the half SituationTriageScenario relies on:
      // there the identity a run assumes is routinely an administrator, and only the scenario
      // refuses.
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.SCHEDULED_TASK, "ou_admin"))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.SUBAGENT, "ou_admin"))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);

      // The kind of run, but not the person.
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, "ou_somebody"))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
      // And a run with nobody behind it is nobody's administrator.
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, null))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
    }
  }

  @Test
  @DisplayName("a scenario written elsewhere gets no admin tools by saying nothing")
  void aConsumerScenarioIsFailClosed() {
    // Why the admin question is not folded into offers(): this scenario allows every tool it is
    // asked about, which is what an SDK consumer's scenario does by default. It must still not
    // hand out an admin tool.
    final var saysNothing = new AgentScenario() {};

    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      assertThat(provider(context, "ou_admin").resolveScenarioTools(saysNothing, "ou_admin"))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class)
          .hasAtLeastOneElementOfType(LibraryTool.class);
    }
  }

  @Test
  @DisplayName("listing a deployment's tools names no admin tool, since it names no person")
  void theListingOverloadHoldsNoAdminTool() {
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      assertThat(provider(context, "ou_admin").resolveScenarioTools(BuiltInScenarios.CHAT))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
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
    return provider(context, null);
  }

  private static AgentToolsProvider provider(
      final AnnotationConfigApplicationContext context, final String admin) {
    return new AgentToolsProvider(
        mock(UserWorkspaceFactory.class),
        mock(McpServerConfigRepo.class),
        mock(McpClientFactory.class),
        context,
        mock(SpringAgentProperties.class),
        new Admins(
            new SpringAgentProperties(
                null,
                new SpringAgentProperties.Ai(
                    admin == null ? Set.of() : Set.of(admin),
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                Locale.ENGLISH)),
        mock(org.springframework.beans.factory.ObjectProvider.class));
  }
}
