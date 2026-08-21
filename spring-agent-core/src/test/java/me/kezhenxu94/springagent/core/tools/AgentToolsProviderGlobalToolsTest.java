package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-wide {@link ToolCallbackProvider} beans — Spring AI's MCP client auto-configuration,
 * fed by {@code spring.ai.mcp.client.*}, being the one every consumer gets without writing any code
 * — reach a run alongside the tools the run builds for itself.
 */
class AgentToolsProviderGlobalToolsTest {

  @TempDir Path workspace;

  @Configuration
  static class GlobalTools {
    @Bean
    ToolCallbackProvider globalProvider() {
      return () -> new ToolCallback[] {stubCallback("global_search")};
    }
  }

  @Test
  @DisplayName("tools from a global provider bean are offered to the run")
  void globalProviderToolsAreComposed() throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forOwner("ou_1")).thenReturn(new UserHome(workspace));
    try (var context = new AnnotationConfigApplicationContext(GlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties());

      final var composition =
          provider.compose(
              AgentRequest.builder()
                  .scenario(BuiltInScenarios.CHAT)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("Search for something."))
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true);

      assertThat(composition.tools())
          .filteredOn(ToolCallback.class::isInstance)
          .extracting(tool -> ((ToolCallback) tool).getToolDefinition().name())
          .contains("global_search");
      // Nothing here is the run's to close: the provider's clients live as long as the context.
      assertThat(composition.mcpTools().clients()).isEmpty();
    }
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
        null,
        new Ai(
            null,
            Set.of(),
            Map.of(),
            null,
            new Tools(new AskUserQuestion(true, null), null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH);
  }
}
