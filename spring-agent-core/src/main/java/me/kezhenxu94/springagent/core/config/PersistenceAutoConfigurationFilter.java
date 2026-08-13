package me.kezhenxu94.springagent.core.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Keeps the auto-configuration of the unselected persistence backend out of the context, for a
 * deployment that carries both.
 *
 * <p>Spring Boot's auto-configurations activate on classpath presence. For MongoDB that is merely
 * wasteful, but for JDBC it is fatal: the JPA starter brings HikariCP, which switches {@code
 * DataSourceAutoConfiguration} on, which then fails a MongoDB deployment that has no {@code
 * spring.datasource.url}. That invariant used to be enforced by keeping every connection pool off
 * the classpath, and this filter is what enforces it now.
 *
 * <p>It does what a {@code spring.autoconfigure.exclude} entry would, except that it follows the
 * selected backend instead of having to be repeated by every application using this module.
 *
 * <p>It only acts when <em>both</em> {@code spring-agent-persistence-*} modules are present, which
 * is the only case that is ambiguous. A consumer who took a single backend module has nothing of
 * the other's to filter, and may well have brought Spring Data JPA for reasons of their own —
 * silently dropping their {@code DataSourceAutoConfiguration} would be a bug, not a service.
 */
public class PersistenceAutoConfigurationFilter
    implements AutoConfigurationImportFilter, EnvironmentAware, BeanClassLoaderAware {

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
          // repository from JdbcChatMemoryAutoConfiguration: the context would end up with two
          // ChatMemoryRepository beans and no way to choose.
          "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryAutoConfiguration",
          "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryIndexCreatorAutoConfiguration");

  private Environment environment;
  private ClassLoader classLoader;

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public void setBeanClassLoader(final ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  @Override
  public boolean[] match(
      final String[] autoConfigurationClasses, final AutoConfigurationMetadata metadata) {
    final var matches = new boolean[autoConfigurationClasses.length];
    Arrays.fill(matches, true);

    final var bothPresent =
        PersistenceBackendResolver.present(Type.JDBC, classLoader)
            && PersistenceBackendResolver.present(Type.MONGODB, classLoader);
    if (!bothPresent) {
      return matches;
    }

    // Through the shared resolver, so this and ConditionalOnPersistenceBackend cannot disagree
    // about which backend won: they are evaluated by different Spring mechanisms and have no
    // other way to stay in step.
    final var selected = PersistenceBackendResolver.resolve(environment, classLoader);
    final var unwanted =
        selected == Type.JDBC ? MONGODB_AUTO_CONFIGURATIONS : JDBC_AUTO_CONFIGURATIONS;

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
