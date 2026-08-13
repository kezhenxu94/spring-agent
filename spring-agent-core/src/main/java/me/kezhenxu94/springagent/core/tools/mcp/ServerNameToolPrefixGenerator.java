package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;

/**
 * Prefixes every tool with the sanitized name of the MCP server it came from (set as the client's
 * own {@code clientInfo} in {@link McpClientFactory#createAndInitialize}), instead of Spring AI's
 * default connection-identity heuristic. This keeps tool names stable and always distinguishes
 * servers that report identical MCP {@code Implementation} info during the handshake — e.g. two
 * registered environments (staging/prod) of the same underlying MCP server.
 */
public class ServerNameToolPrefixGenerator implements McpToolNamePrefixGenerator {

  private static final int MAX_TOOL_NAME_LENGTH = 64;
  private static final int TRUNCATION_SUFFIX_HASH_LENGTH = 8;

  @Override
  public String prefixedToolName(
      final McpConnectionInfo connectionInfo, final McpSchema.Tool tool) {
    final var prefixed = connectionInfo.clientInfo().name() + "_" + tool.name();
    if (prefixed.length() <= MAX_TOOL_NAME_LENGTH) {
      return prefixed;
    }
    // Truncating a long name naively can make two different tools collide (or drop the part of
    // the name that made them distinct). Append a hash of the untruncated name instead, so the
    // result still deterministically maps back to a single (server, tool) pair.
    final var suffix = "_" + McpClientFactory.hashHex(prefixed, TRUNCATION_SUFFIX_HASH_LENGTH);
    return prefixed.substring(0, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
  }
}
