package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpConnectionInfo;

class ServerNameToolPrefixGeneratorTest {

  private final ServerNameToolPrefixGenerator generator = new ServerNameToolPrefixGenerator();

  @Test
  @DisplayName("prefixes with the client's server-derived name when the result fits")
  void prefixesWithoutTruncationWhenShort() {
    final var name = generator.prefixedToolName(connectionInfo("mcp_ab12"), tool("search"));
    assertThat(name).isEqualTo("mcp_ab12_search");
  }

  @Test
  @DisplayName("truncated names stay within the 64-char limit and stay distinct across tools")
  void truncationPreservesUniqueness() {
    final var info = connectionInfo("mcp_ab12");
    final var longToolNameA = "a".repeat(80) + "_one";
    final var longToolNameB = "a".repeat(80) + "_two";

    final var nameA = generator.prefixedToolName(info, tool(longToolNameA));
    final var nameB = generator.prefixedToolName(info, tool(longToolNameB));

    assertThat(nameA).hasSizeLessThanOrEqualTo(64);
    assertThat(nameB).hasSizeLessThanOrEqualTo(64);
    assertThat(nameA).isNotEqualTo(nameB);
  }

  private McpConnectionInfo connectionInfo(final String clientName) {
    return McpConnectionInfo.builder()
        .clientCapabilities(McpSchema.ClientCapabilities.builder().build())
        .clientInfo(new McpSchema.Implementation(clientName, "1.0.0"))
        .build();
  }

  private McpSchema.Tool tool(final String name) {
    final var inputSchema =
        new McpSchema.JsonSchema(
            "object",
            java.util.Map.of(),
            java.util.List.of(),
            false,
            java.util.Map.of(),
            java.util.Map.of());
    return McpSchema.Tool.builder().name(name).inputSchema(inputSchema).build();
  }
}
