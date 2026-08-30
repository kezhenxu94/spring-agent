package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.advisors.AutoSkillToolsAdvisor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.Skills;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The offer is only worth making to a run that could act on it, and both of the things that decide
 * that are silent when they are wrong: a deployment that turned it off would still see the
 * paragraph appended, and a run without the skill tools would be told to write something it has no
 * way to write, which costs the user a turn to discover.
 */
class AgentToolsProviderSkillOfferTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("a run that was given the skill tools is given the advisor that offers a skill")
  void offeredByDefault() throws Exception {
    assertThat(compose(true, true).advisors()).anyMatch(AutoSkillToolsAdvisor.class::isInstance);
  }

  @Test
  @DisplayName("a deployment that does not want the offer made gets no advisor")
  void turnedOff() throws Exception {
    assertThat(compose(false, true).advisors()).noneMatch(AutoSkillToolsAdvisor.class::isInstance);
  }

  @Test
  @DisplayName("a run with no way to write a skill is never asked to offer one")
  void withoutTheSkillTools() throws Exception {
    assertThat(compose(true, false).advisors()).noneMatch(AutoSkillToolsAdvisor.class::isInstance);
  }

  private AgentToolsProvider.AgentComposition compose(
      final boolean offerAfterExpensiveRuns, final boolean withSkillTools) throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    try (var context = new AnnotationConfigApplicationContext()) {
      if (withSkillTools) {
        // Registered as the application registers it, annotation and all, since it is the
        // annotation that puts it in front of the scenario and so into the composed list.
        context.registerBean(
            SkillManagementTools.class, () -> new SkillManagementTools(workspaces));
      }
      context.refresh();
      final var properties = properties(offerAfterExpensiveRuns);
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties,
              new Admins(properties),
              mock(ObjectProvider.class));
      return provider.compose(
          AgentRequest.builder()
              .scenario(BuiltInScenarios.CHAT)
              .userId("ou_1")
              .chatId("oc_1")
              .userMessage(user -> user.text("something expensive"))
              .build(),
          Map.of(),
          todos -> {},
          null,
          false,
          references -> {});
    }
  }

  private static SpringAgentProperties properties(final boolean offerAfterExpensiveRuns) {
    return new SpringAgentProperties(
        null,
        new Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            new Tools(null, null, new Skills(offerAfterExpensiveRuns, 0), null, null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH,
        null);
  }
}
