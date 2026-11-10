package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tools a surface brings on the request — a phone's own — are composed with the run's other
 * callbacks, and so answer to the same two rules: the scenario rules on them by name, and a name
 * that is already taken fails the run.
 */
class AgentToolsProviderRequestToolCallbacksTest {

  @TempDir Path workspace;

  @Configuration
  static class GlobalTools {
    @Bean
    ToolCallbackProvider globalProvider() {
      return () -> new ToolCallback[] {stubCallback("global_search")};
    }
  }

  private static final AgentScenario NO_DEVICE_TOOLS =
      new AgentScenario() {
        @Override
        public boolean offers(final ToolCallback tool) {
          return !tool.getToolDefinition().name().startsWith("device_");
        }
      };

  @Test
  @DisplayName("a request's own callbacks are offered to the run")
  void requestCallbacksAreComposed() throws Exception {
    final var tools = compose(BuiltInScenarios.CHAT, stubCallback("device_location"));
    assertThat(callbackNames(tools)).contains("device_location", "global_search");
  }

  @Test
  @DisplayName("the scenario rules on a request's callbacks, by name, like any other callback")
  void theScenarioRulesOnRequestCallbacks() throws Exception {
    final var tools = compose(NO_DEVICE_TOOLS, stubCallback("device_location"));
    assertThat(callbackNames(tools)).doesNotContain("device_location").contains("global_search");
  }

  @Test
  @DisplayName("a request's callback named like a tool the run already has fails the run")
  void aCollidingNameIsRefused() {
    assertThatThrownBy(() -> compose(BuiltInScenarios.CHAT, stubCallback("global_search")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("global_search");
  }

  private Object[] compose(final AgentScenario scenario, final ToolCallback callback)
      throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    when(workspaces.forOwner(eq("ou_1"))).thenReturn(new UserHome(workspace));
    try (var context = new AnnotationConfigApplicationContext(GlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              new TenantWrites(properties(), new Admins(properties())),
              TestI18n.english(),
              mock(ObjectProvider.class));
      return provider
          .compose(
              AgentRequest.builder()
                  .scenario(scenario)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("Where am I?"))
                  .toolCallback(callback)
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true,
              references -> {})
          .tools();
    }
  }

  private static List<String> callbackNames(final Object[] tools) {
    return Arrays.stream(tools)
        .filter(ToolCallback.class::isInstance)
        .map(tool -> ((ToolCallback) tool).getToolDefinition().name())
        .toList();
  }

  private static ToolCallback stubCallback(final String name) {
    final var definition =
        ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "";
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return "";
      }
    };
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            null,
            Map.of(),
            null,
            null,
            new Tools(new AskUserQuestion(true, null), null, null, null, null),
            "You are an agent.",
            null,
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
