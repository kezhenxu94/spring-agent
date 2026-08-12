package me.kezhenxu94.springagent.tools;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.tools.mcp")
public record McpProperties(List<String> trustedHosts) {

  public McpProperties {
    if (trustedHosts == null) {
      trustedHosts = List.of();
    } else {
      trustedHosts =
          trustedHosts.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }
  }
}
