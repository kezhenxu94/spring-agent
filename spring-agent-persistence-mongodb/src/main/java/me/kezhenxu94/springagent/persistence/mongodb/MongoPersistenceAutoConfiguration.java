package me.kezhenxu94.springagent.persistence.mongodb;

import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoScheduledTaskRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Registers the MongoDB implementations of the repository contracts {@code spring-agent-core}
 * declares. The counterpart of {@code JdbcPersistenceAutoConfiguration}; see it for the reasoning
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
public class MongoPersistenceAutoConfiguration {}
