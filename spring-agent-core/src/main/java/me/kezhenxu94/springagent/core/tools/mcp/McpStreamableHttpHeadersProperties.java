package me.kezhenxu94.springagent.core.tools.mcp;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reads the {@code headers} map that {@code spring.ai.mcp.client.streamable-http.connections.*}
 * itself doesn't support (only {@code url}/{@code endpoint} are bound by Spring AI's own {@code
 * McpStreamableHttpClientProperties}). Bound to the same prefix so both classes can read from the
 * same YAML block; unrecognized properties are ignored by default, so this doesn't conflict with
 * Spring AI's own binding of {@code url}/{@code endpoint}.
 */
@ConfigurationProperties(prefix = "spring.ai.mcp.client.streamable-http")
public record McpStreamableHttpHeadersProperties(Map<String, Connection> connections) {

  public McpStreamableHttpHeadersProperties {
    connections = connections == null ? Map.of() : connections;
  }

  public record Connection(Map<String, String> headers) {

    public Connection {
      headers = headers == null ? Map.of() : headers;
    }
  }
}
