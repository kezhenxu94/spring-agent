package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The condition with {@code app.persistence.type} left unset, which is the path neither application
 * in this repository takes: {@code spring-agent-app} defaults the property to {@code jdbc} and
 * {@code spring-agent-cli} sets it outright, so both always hand the condition a value. An SDK
 * consumer that depends on one backend module and configures nothing — the arrangement {@link
 * ConditionalOnPersistenceBackend} documents as the ordinary one — is the only caller that reaches
 * it, and so the only one that can be broken by it.
 */
class PersistenceBackendConditionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JdbcOnly.class, MongoOnly.class));

  /**
   * The property being absent means "let the classpath decide", not "no backend", so the condition
   * has to treat it as a question it was not asked rather than as a value to compare against.
   */
  @Test
  void evaluatesWithTheTypePropertyUnset() {
    runner.run(
        context ->
            assertThat(context)
                .as("an unset app.persistence.type must not fail the condition")
                .hasNotFailed());
  }

  /** No backend module is on this module's test classpath, so the fallback stands. */
  @Test
  void fallsBackToJdbcWithTheTypePropertyUnset() {
    runner.run(
        context -> {
          assertThat(context).hasBean("jdbcMarker");
          assertThat(context).doesNotHaveBean("mongoMarker");
        });
  }

  /**
   * The other half of the branch being guarded: naming a backend whose module was never added still
   * has to report that, rather than silently leaving the beans out.
   */
  @Test
  void reportsABackendWhoseModuleIsAbsent() {
    runner
        .withPropertyValues("app.persistence.type=mongodb")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("mongoMarker");
            });
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnPersistenceBackend(Type.JDBC)
  static class JdbcOnly {

    @Bean
    String jdbcMarker() {
      return "jdbc";
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnPersistenceBackend(Type.MONGODB)
  static class MongoOnly {

    @Bean
    String mongoMarker() {
      return "mongodb";
    }
  }
}
