package me.kezhenxu94.springagent.appcli.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param userId who the agent is talking to, which scopes their workspace, memories and stored
 *     credentials. Defaults to the OS user in {@code application.yaml}.
 * @param color whether to use colour and the marker glyphs. Only ever narrows what the terminal
 *     itself reports: a dumb terminal or piped output stays plain whatever this says.
 * @param locale which language the command line speaks. Defaults to the operating system's, so
 *     setting it is only for speaking a different language from the rest of the desktop.
 */
@ConfigurationProperties(prefix = "app.cli")
public record CliProperties(String userId, boolean color, Locale locale) {

  public CliProperties {
    if (userId == null || userId.isBlank()) {
      userId = System.getProperty("user.name", "user");
    }
  }
}
