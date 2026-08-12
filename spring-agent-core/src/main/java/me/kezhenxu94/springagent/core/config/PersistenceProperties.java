package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which database the agent uses: MCP server configs, scheduled tasks, published resources, Feishu
 * message state and the conversation history, all of them together. One property rather than one
 * per concern, because a deployment that runs MongoDB wants everything there, and one that does not
 * wants nothing there.
 *
 * @param type which backend stores them. The default, {@link Type#JDBC}, is configured through the
 *     standard {@code spring.datasource} properties and needs no server; {@link Type#MONGODB} is
 *     the alternative for a deployment that already runs one.
 */
@ConfigurationProperties(prefix = "app.persistence")
public record PersistenceProperties(Type type) {

  public PersistenceProperties {
    if (type == null) {
      type = Type.JDBC;
    }
  }

  public enum Type {
    JDBC,
    MONGODB
  }
}
