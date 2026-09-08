package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * A chosen {@code toolPrefix} is what makes two MCP servers able to name their tools the same, and
 * a request carrying two tools of one name is one Spring AI refuses. Composition has to say so
 * itself, and has to leave no client open when it does.
 */
class AgentToolsProviderDuplicateToolNamesTest {

  @TempDir Path workspace;

  @Configuration
  static class NoGlobalTools {}

  @Test
  @DisplayName("two servers sharing a prefix are refused before a single handshake is paid for")
  void refusesDuplicatePrefixesWithoutConnecting() {
    final var factory = mock(McpClientFactory.class);

    assertThatThrownBy(
            () ->
                compose(
                    factory,
                    List.of(server("ops-staging", "ops"), server("ops-prod", "ops")),
                    List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContainingAll("ops-staging", "ops-prod", "ops_*", "toolPrefix");

    verify(factory, never()).createAndInitialize(any(), any());
  }

  @Test
  @DisplayName("distinct prefixes whose tool names still collide close every client they opened")
  void closesClientsWhenTheAssembledNamesCollide() {
    // "a" + "b_c" and "a_b" + "c" are both "a_b_c": distinct prefixes, one tool name, which only
    // listing each server's tools can reveal.
    final var first = mockClient("a", "b_c");
    final var second = mockClient("a_b", "c");
    final var factory = mock(McpClientFactory.class);
    when(factory.createAndInitialize(any(), any())).thenReturn(first, second);

    assertThatThrownBy(
            () ->
                compose(
                    factory,
                    List.of(server("first", "a"), server("second", "a_b")),
                    List.of(first, second)))
        .isInstanceOf(IllegalStateException.class);

    // Nothing was handed back to close them, so composition had to.
    verify(first).close();
    verify(second).close();
  }

  @Test
  @DisplayName("two servers offering the same tool under distinct prefixes compose")
  void distinctPrefixesCompose() {
    final var staging = mockClient("ops_staging", "search");
    final var prod = mockClient("ops", "search");
    final var factory = mock(McpClientFactory.class);
    when(factory.createAndInitialize(any(), any())).thenReturn(staging, prod);

    assertThatCode(
            () ->
                compose(
                    factory,
                    List.of(server("ops-staging", "ops_staging"), server("ops-prod", "ops")),
                    List.of(staging, prod)))
        .doesNotThrowAnyException();
  }

  @SuppressWarnings("unchecked")
  private void compose(
      final McpClientFactory factory,
      final List<McpServerConfig> configs,
      final List<McpSyncClient> expectedClients)
      throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    final var repo = mock(McpServerConfigRepo.class);
    when(repo.findAccessibleTo(any(), any())).thenReturn(configs);

    try (var context = new AnnotationConfigApplicationContext(NoGlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              repo,
              factory,
              context,
              properties(),
              new Admins(properties()),
              mock(ObjectProvider.class));

      final var composition =
          provider.compose(
              AgentRequest.builder()
                  .scenario(BuiltInScenarios.CHAT)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("hi"))
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true,
              references -> {});
      assertThat(composition.mcpTools().callbacks()).hasSize(expectedClients.size());
      composition.mcpTools().close();
    }
  }

  private static McpServerConfig server(final String name, final String toolPrefix) {
    return McpServerConfig.builder()
        .ownerId("ou_1")
        .name(name)
        .toolPrefix(toolPrefix)
        .url("https://ops.example.com/mcp")
        .enabled(true)
        .build();
  }

  /** A client reporting {@code prefix} as its own name, exactly as the factory builds it. */
  private static McpSyncClient mockClient(final String prefix, final String toolName) {
    final var inputSchema =
        new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of());
    final var tool = McpSchema.Tool.builder().name(toolName).inputSchema(inputSchema).build();
    final var client = mock(McpSyncClient.class);
    when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
    when(client.getClientInfo()).thenReturn(new McpSchema.Implementation(prefix, "1.0.0"));
    when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
    return client;
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
