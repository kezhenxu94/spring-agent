package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.jpa.JpaScheduledTaskRepo;
import me.kezhenxu94.springagent.core.dao.repo.mongo.MongoScheduledTaskRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Registers the repositories of whichever backend {@code app.persistence.type} names, and only
 * those.
 *
 * <p>The two branches are mutually exclusive by construction: each repository contract has exactly
 * one implementation on the classpath per branch, so the application's injection points resolve
 * without qualifiers. Enabling both would give every contract two candidate beans.
 *
 * <p>Note for native images: {@code @ConditionalOnProperty} is evaluated during AOT processing, so
 * the backend is fixed when the image is built rather than when it runs. See the {@code
 * -PnativeBackends} flag in {@code springagent.native.gradle}.
 */
@AutoConfiguration
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {

  /** The default, and what an existing deployment keeps when it sets nothing. */
  @AutoConfiguration
  @ConditionalOnProperty(prefix = "app.persistence", name = "type", havingValue = "mongodb")
  @EnableMongoRepositories(basePackageClasses = MongoScheduledTaskRepo.class)
  @EnableMongoAuditing
  public static class Mongo {}

  /**
   * {@code @EntityScan} is needed because the models live in another package than this
   * configuration, and without the Boot plugin's auto-detection a library module has no persistence
   * unit root to scan.
   */
  @AutoConfiguration
  @ConditionalOnProperty(
      prefix = "app.persistence",
      name = "type",
      havingValue = "jdbc",
      matchIfMissing = true)
  @EnableJpaRepositories(basePackageClasses = JpaScheduledTaskRepo.class)
  @EntityScan(basePackageClasses = McpServerConfig.class)
  public static class Jdbc {}
}
