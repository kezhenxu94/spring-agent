package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
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
  static class AnAdminTool {}

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

    /**
     * Declared on the factory method rather than the class, which is the case worth covering: it is
     * how {@code PlaybookTools} is registered, and reading the attribute off the bean's own class
     * would see the default here and offer it to everybody.
     */
    @Bean
    @AgentTool(admin = true)
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
  @DisplayName("an admin tool goes to an administrator, and to nobody else")
  void anAdminToolGoesToAnAdministrator() {
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      final var provider = provider(context, "ou_admin");

      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, "ou_admin"))
          .hasAtLeastOneElementOfType(AnAdminTool.class);
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, "ou_somebody"))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
      // A run with nobody behind it is nobody's administrator.
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.CHAT, null))
          .doesNotHaveAnyElementsOfTypes(AnAdminTool.class);
    }
  }

  @Test
  @DisplayName("an administrator keeps them in their own unattended runs, which is the point")
  void anAdministratorKeepsThemWhenDelegating() {
    // Deliberate rather than overlooked. A scheduled task and a subagent both act on a brief this
    // same administrator wrote, so withholding here would only stop them deferring or delegating
    // work they could do in the chat they are sitting in.
    //
    // What must never happen is an identity that reads strangers' text being an administrator, and
    // that is refused by SituationSweeper at startup — no code on this path can tell such a run
    // from an administrator's own.
    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      final var provider = provider(context, "ou_admin");

      assertThat(provider.resolveScenarioTools(BuiltInScenarios.SCHEDULED_TASK, "ou_admin"))
          .hasAtLeastOneElementOfType(AnAdminTool.class);
      assertThat(provider.resolveScenarioTools(BuiltInScenarios.SUBAGENT, "ou_admin"))
          .hasAtLeastOneElementOfType(AnAdminTool.class);
    }
  }

  @Test
  @DisplayName("a scenario written elsewhere rules on an admin tool like any other")
  void aConsumerScenarioRulesOnThemLikeAnyOther() {
    // A consumer's scenario decides these the way it decides everything else: say nothing and an
    // administrator gets them, say no and nobody does. There is no separate admin question on the
    // interface to forget about, because the identity is the boundary.
    //
    // What a scenario cannot do any more is rule on the category — @AgentTool(admin) is on the
    // bean definition, not the type, so offers() sees only the object. Refusing one means naming
    // its class, as here.
    final var saysNothing = new AgentScenario() {};
    final var refuses =
        new AgentScenario() {
          @Override
          public boolean offers(final Object tool) {
            return !(tool instanceof AnAdminTool);
          }
        };

    try (var context = new AnnotationConfigApplicationContext(Tools.class)) {
      assertThat(provider(context, "ou_admin").resolveScenarioTools(saysNothing, "ou_admin"))
          .hasAtLeastOneElementOfType(AnAdminTool.class);
      assertThat(provider(context, "ou_admin").resolveScenarioTools(refuses, "ou_admin"))
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

  @Test
  @DisplayName("a scheduled run is the only one offered the tools that act on the firing task")
  void onlyAFiringActsOnItself() {
    final var firingTool =
        new FiringScheduledTaskTool(
            mock(ScheduledTaskRepo.class),
            mock(ScheduledTaskService.class),
            mock(CoreMessages.class));

    assertThat(BuiltInScenarios.SCHEDULED_TASK.offers(firingTool)).isTrue();
    assertThat(BuiltInScenarios.CHAT.offers(firingTool)).isFalse();
    assertThat(BuiltInScenarios.SUBAGENT.offers(firingTool)).isFalse();
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
                Locale.ENGLISH,
                null,
                null)),
        mock(org.springframework.beans.factory.ObjectProvider.class));
  }
}
