package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;

/**
 * Reproduces the original bug report: two MCP server registrations (e.g. two instances of the same
 * backend) that expose an identical tool set and report identical server {@code Implementation}
 * info during the handshake. Exercises the exact assembly path used for a real chat request (see
 * {@link me.kezhenxu94.springagent.core.tools.AgentToolsProvider}), via {@link
 * ServerNameToolPrefixGenerator}.
 */
class AgentToolsProviderMcpAssemblyTest {

  @Test
  @DisplayName(
      "does not collide when two servers report identical tools and unicode-only-differing names")
  void distinguishesServersWithIdenticalToolsAndUnicodeOnlyNames() {
    final var inputSchema =
        new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of());
    final var tool = McpSchema.Tool.builder().name("search").inputSchema(inputSchema).build();

    final var beaker = mockClientFor("github-mcp(🧪)", tool);
    final var green = mockClientFor("github-mcp(🟢)", tool);

    final var callbacks =
        SyncMcpToolCallbackProvider.builder()
            .mcpClients(List.of(beaker, green))
            .toolNamePrefixGenerator(new ServerNameToolPrefixGenerator())
            .build()
            .getToolCallbacks();

    assertThat(callbacks).hasSize(2);
    final var names =
        List.of(callbacks[0].getToolDefinition().name(), callbacks[1].getToolDefinition().name());
    assertThat(names).doesNotHaveDuplicates();
    assertThat(names.get(0)).isNotEqualTo(names.get(1));
  }

  private McpSyncClient mockClientFor(final String serverName, final McpSchema.Tool tool) {
    final var client = mock(McpSyncClient.class);
    when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
    when(client.getClientInfo())
        .thenReturn(new McpSchema.Implementation(McpClientFactory.hashPrefix(serverName), "1.0.0"));
    when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
    return client;
  }
}
