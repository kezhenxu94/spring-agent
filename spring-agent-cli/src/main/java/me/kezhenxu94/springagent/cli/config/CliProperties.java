package me.kezhenxu94.springagent.cli.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param userId who the agent is talking to, which scopes their workspace, memories and stored
 *     credentials. Defaults to the OS user in {@code application.yaml}.
 * @param color whether to use colour and the marker glyphs. Only ever narrows what the terminal
 *     itself reports: a dumb terminal or piped output stays plain whatever this says.
 */
@ConfigurationProperties(prefix = "app.cli")
public record CliProperties(String userId, boolean color) {

  public CliProperties {
    if (userId == null || userId.isBlank()) {
      userId = System.getProperty("user.name", "user");
    }
  }
}
