package me.kezhenxu94.springagent.persistence.mongodb;

import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import me.kezhenxu94.springagent.persistence.mongodb.aot.MongoPersistenceRuntimeHints;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoChatMemoryRepo;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoProcessedMessageRepo;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoScheduledTaskRepo;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Registers the MongoDB implementations of the repository contracts {@code spring-agent-core}
 * declares. The counterpart of {@code JpaPersistenceAutoConfiguration}; see it for the reasoning
 * this pair shares.
 *
 * <p>This class doubles as the marker that tells {@code spring-agent-core} the MongoDB backend is
 * available at all — see {@code PersistenceBackendResolver}. Renaming or moving it changes the
 * classpath-based selection, so do both together.
 *
 * <p>No {@code @EntityScan} counterpart is needed: Spring Data MongoDB maps the domain models on
 * first use rather than from a scanned persistence unit.
 */
@AutoConfiguration
@ConditionalOnPersistenceBackend(Type.MONGODB)
@EnableMongoRepositories(basePackageClasses = MongoScheduledTaskRepo.class)
@EnableMongoAuditing
// Declared here because this module supersedes the auto-configuration that used to declare them —
// see chatMemoryRepository below. Spring AI's index creator, which is left in place, is what reads
// them, and without this it fails startup on a missing properties bean rather than degrading.
@EnableConfigurationProperties(MongoChatMemoryProperties.class)
@ImportRuntimeHints(MongoPersistenceRuntimeHints.class)
public class MongoPersistenceAutoConfiguration {

  /**
   * Declared rather than found by the repository scan above, because it is a class and not a Spring
   * Data interface — see {@link MongoProcessedMessageRepo} for why it is one.
   */
  @Bean
  MongoProcessedMessageRepo mongoProcessedMessageRepo(final MongoTemplate mongoTemplate) {
    return new MongoProcessedMessageRepo(mongoTemplate);
  }

  /**
   * The conversation history, in place of Spring AI's own MongoDB repository — see {@link
   * MongoChatMemoryRepo} for what that one gets wrong and why it cannot be fixed from outside.
   *
   * <p>Spring AI's bean is kept out by {@code PersistenceAutoConfigurationFilter} rather than by a
   * condition here, because its {@code @ConditionalOnMissingBean} is on its own concrete type and
   * so would not back off in front of this: the context would end up with two {@code
   * ChatMemoryRepository} beans and no way to choose. Its index creator is left in place, since the
   * indexes and the TTL it maintains are on the same collection this writes.
   */
  @Bean
  @ConditionalOnMissingBean
  ChatMemoryRepository chatMemoryRepository(final MongoTemplate mongoTemplate) {
    return new MongoChatMemoryRepo(mongoTemplate);
  }
}
