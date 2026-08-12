package me.kezhenxu94.springagent.core.config;

import java.util.List;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Keeps the auto-configuration of the persistence backend that {@code app.persistence.type} did not
 * select out of the context.
 *
 * <p>Both backends are on the classpath, and Spring Boot's auto-configurations activate on
 * classpath presence. For MongoDB that is merely wasteful, but for JDBC it is fatal: the JPA
 * starter brings HikariCP, which switches {@code DataSourceAutoConfiguration} on, which then fails
 * a MongoDB deployment that has no {@code spring.datasource.url}. That invariant used to be
 * enforced by keeping every connection pool off the classpath — see the exclusions in the build
 * files — and this filter is what enforces it now.
 *
 * <p>It does what a {@code spring.autoconfigure.exclude} entry would, except that it follows the
 * property instead of having to be repeated by every application using this module.
 */
public class PersistenceAutoConfigurationFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {

  /** Dropped unless the JDBC backend is selected. */
  private static final Set<String> JDBC_AUTO_CONFIGURATIONS =
      Set.of(
          "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
          "org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration",
          "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
          "org.springframework.boot.hibernate.autoconfigure.metrics.HibernateMetricsAutoConfiguration",
          "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration");

  /**
   * Dropped when the JDBC backend is selected, so a JDBC-only deployment needs no MongoDB server.
   */
  private static final Set<String> MONGODB_AUTO_CONFIGURATIONS =
      Set.of(
          "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
          "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration",
          "org.springframework.boot.mongodb.autoconfigure.health.MongoHealthContributorAutoConfiguration",
          "org.springframework.boot.mongodb.autoconfigure.metrics.MongoMetricsAutoConfiguration",
          "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
          "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveAutoConfiguration",
          "org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration",
          "org.springframework.boot.data.mongodb.autoconfigure.DataMongoReactiveRepositoriesAutoConfiguration",
          // Spring AI's MongoDB chat memory. It guards its bean with @ConditionalOnMissingBean on
          // the concrete MongoChatMemoryRepository, so it would not back off in front of the JDBC
          // repository from ChatMemoryConfiguration: the context would end up with two
          // ChatMemoryRepository beans and no way to choose.
          "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryAutoConfiguration",
          "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryIndexCreatorAutoConfiguration");

  private Environment environment;

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public boolean[] match(
      final String[] autoConfigurationClasses, final AutoConfigurationMetadata metadata) {
    final var configured = environment.getProperty("app.persistence.type", Type.JDBC.name());
    final var jdbcSelected = Type.JDBC.name().equalsIgnoreCase(configured.trim());
    final var unwanted = jdbcSelected ? MONGODB_AUTO_CONFIGURATIONS : JDBC_AUTO_CONFIGURATIONS;

    final var matches = new boolean[autoConfigurationClasses.length];
    for (int i = 0; i < matches.length; i++) {
      final var candidate = autoConfigurationClasses[i];
      matches[i] = candidate == null || !unwanted.contains(candidate);
    }
    return matches;
  }

  /** Exposed for the test that asserts the class names above still exist on the classpath. */
  static List<Set<String>> filteredAutoConfigurations() {
    return List.of(JDBC_AUTO_CONFIGURATIONS, MONGODB_AUTO_CONFIGURATIONS);
  }
}
