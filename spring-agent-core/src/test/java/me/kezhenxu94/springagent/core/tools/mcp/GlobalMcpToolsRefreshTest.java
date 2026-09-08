package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * An application-wide MCP server — one configured under {@code spring.ai.mcp.client.*} and
 * published as a {@link org.springframework.ai.tool.ToolCallbackProvider} bean — whose tool set
 * changes while the application runs.
 *
 * <p>Upstream's provider lists the tools once and then answers from a cache it invalidates only on
 * a {@code notifications/tools/list_changed} the server has to push. A server that renames a tool
 * and says nothing — the ordinary case for one redeployed behind a URL — leaves every later run
 * offered the snapshot taken at startup. The tool search then never re-indexes either: the advisor
 * fingerprints the names and descriptions it is handed, and those never move.
 */
class GlobalMcpToolsRefreshTest {

  @TempDir Path workspace;

  /** What the server offers, swapped between the two runs below. */
  private static final McpSchema.ListToolsResult BEFORE = listing("search_issues");

  private static final McpSchema.ListToolsResult AFTER = listing("find_issues");

  @Configuration
  static class AGlobalMcpServer {
    @Bean
    org.springframework.ai.tool.ToolCallbackProvider globalMcpProvider() {
      final var client = mock(McpSyncClient.class);
      when(client.getClientCapabilities())
          .thenReturn(McpSchema.ClientCapabilities.builder().build());
      when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("issues", "1.0.0"));
      when(client.listTools()).thenReturn(BEFORE, AFTER);
      return SyncMcpToolCallbackProvider.builder()
          .mcpClients(List.of(client))
          .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
          .build();
    }
  }

  @Test
  @DisplayName("a run sees the tools an application-wide MCP server offers now, not at startup")
  void aRenamedToolReachesTheNextRun() throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));

    try (var context = new AnnotationConfigApplicationContext(AGlobalMcpServer.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              mock(ObjectProvider.class));

      assertThat(toolNames(provider)).contains("search_issues").doesNotContain("find_issues");
      assertThat(toolNames(provider)).contains("find_issues").doesNotContain("search_issues");
    }
  }

  private List<String> toolNames(final AgentToolsProvider provider) throws Exception {
    final var composition =
        provider.compose(
            AgentRequest.builder()
                .scenario(BuiltInScenarios.CHAT)
                .userId("ou_1")
                .chatId("oc_1")
                .userMessage(user -> user.text("List my issues."))
                .build(),
            Map.of(),
            todos -> {},
            questions -> Map.of(),
            true,
            references -> {});
    return java.util.Arrays.stream(composition.tools())
        .filter(ToolCallback.class::isInstance)
        .map(tool -> ((ToolCallback) tool).getToolDefinition().name())
        .toList();
  }

  private static McpSchema.ListToolsResult listing(final String name) {
    final var schema =
        new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of());
    return new McpSchema.ListToolsResult(
        List.of(McpSchema.Tool.builder().name(name).inputSchema(schema).build()), null);
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            new Tools(new AskUserQuestion(true, null), null, null, null, null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
