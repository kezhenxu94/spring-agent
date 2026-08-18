package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picked up by {@link me.kezhenxu94.springagent.core.config.SpringAgentCoreAutoConfiguration}'s
 * component scan, same as {@link McpClientFactory}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpClientHeadersConfiguration {

  private final McpStreamableHttpHeadersProperties headersProperties;

  @Bean
  McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>
      mcpStreamableHttpHeadersCustomizer() {
    return (name, builder) -> {
      final var connection = headersProperties.connections().get(name);
      final Map<String, String> headers = connection == null ? Map.of() : connection.headers();
      if (headers.isEmpty()) {
        log.info("No extra headers configured for MCP streamable-http connection '{}'", name);
        return;
      }
      // Only log header names, never values - values may carry secrets (e.g. Authorization).
      log.info(
          "Applying {} extra header(s) {} to MCP streamable-http connection '{}'",
          headers.size(),
          headers.keySet(),
          name);
      builder.httpRequestCustomizer(
          (requestBuilder, method, uri, body, context) -> headers.forEach(requestBuilder::header));
    };
  }
}
