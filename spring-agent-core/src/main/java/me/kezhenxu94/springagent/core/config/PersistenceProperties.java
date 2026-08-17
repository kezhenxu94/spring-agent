package me.kezhenxu94.springagent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which database the agent uses: MCP server configs, scheduled tasks, published resources, shell
 * credentials and the conversation history, all of them together. One property rather than one per
 * concern, because a deployment that runs MongoDB wants everything there, and one that does not
 * wants nothing there. It is also why the persistence modules are split by backend rather than by
 * concern: chat memory could not be chosen separately even if it had a module of its own.
 *
 * <p>Setting this is optional. A deployment that depends on exactly one {@code
 * spring-agent-persistence-*} module has already answered the question, and {@link
 * ConditionalOnPersistenceBackend} reads the answer off the classpath. The property is for the
 * deployment that carries more than one and wants to choose at startup.
 *
 * @param type which backend stores them. The default, {@link Type#JPA}, is configured through the
 *     standard {@code spring.datasource} properties and needs no server; {@link Type#MONGODB} and
 *     {@link Type#REDIS} are for a deployment that already runs one.
 */
@ConfigurationProperties(prefix = "app.persistence")
public record PersistenceProperties(Type type) {

  public PersistenceProperties {
    if (type == null) {
      type = Type.JPA;
    }
  }

  public enum Type {
    /**
     * Any relational database Hibernate has a dialect for, reached through Spring Data JPA and
     * defaulting to SQLite. Named for the mapping layer rather than for JDBC because that is what
     * the backend module implements the repository contracts with; the conversation history is the
     * one part of it that goes through plain JDBC, being Spring AI's own repository.
     */
    JPA,
    MONGODB,
    /**
     * Needs Redis 8 or Redis Stack — the chat memory repository is built on RedisJSON and
     * RediSearch — and a server configured to keep what it is given: {@code maxmemory-policy
     * noeviction} plus AOF or RDB. Unlike the other two backends, the store this one writes to can
     * be configured to throw data away, and the agent's own records are not a cache.
     */
    REDIS
  }
}
