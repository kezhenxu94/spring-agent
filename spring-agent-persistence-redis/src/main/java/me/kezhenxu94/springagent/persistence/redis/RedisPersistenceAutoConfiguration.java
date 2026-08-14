package me.kezhenxu94.springagent.persistence.redis;

import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import me.kezhenxu94.springagent.persistence.redis.repo.RedisScheduledTaskRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

/**
 * Registers the Redis implementations of the repository contracts {@code spring-agent-core}
 * declares. Conversation history follows the same backend and is wired by Spring AI's own
 * auto-configuration, which the starter this module depends on brings along.
 *
 * <p>This class doubles as the marker that tells {@code spring-agent-core} the Redis backend is
 * available at all — see {@code PersistenceBackendResolver}. Renaming or moving it changes the
 * classpath-based selection, so do both together.
 *
 * <p><strong>What this backend needs from the server.</strong> Two things the other two backends
 * never have to ask for:
 *
 * <ul>
 *   <li>Redis 8, or Redis Stack on an older line. Spring AI's chat memory repository stores
 *       messages as RedisJSON documents and reads them back through a RediSearch index; against a
 *       server without those modules it fails when it creates the index at startup.
 *   <li>{@code maxmemory-policy noeviction}, and AOF or RDB persistence. These are the agent's own
 *       records, not a cache: a Redis provisioned the usual way for caching, with an {@code
 *       allkeys-lru} policy, is free to evict a user's stored shell credentials or a scheduled task
 *       that has not fired yet, and will do so silently.
 * </ul>
 *
 * <p>One further difference worth knowing when reading data back: Spring Data Redis maintains
 * secondary indexes as separate Redis sets, written non-atomically with the hash they point at. A
 * crash between the two writes can leave an index entry naming a record that does not exist, which
 * surfaces as a query returning fewer results than its index suggested rather than as an error.
 *
 * <p>{@code basePackageClasses} deliberately points into this module's {@code repo} package and not
 * at the contracts in core: the contracts are plain interfaces, and Spring Data would try to build
 * implementations for them too.
 *
 * <p>Keyspaces and secondary indexes are declared as {@code @RedisHash} and {@code @Indexed} on the
 * models, rather than through this annotation's {@code keyspaceConfiguration} and {@code
 * indexConfiguration} attributes, and that is not a matter of taste. Those attributes are wired
 * into the {@code redisMappingContext} bean, which {@code RedisRepositoryConfigurationExtension}
 * registers under a fixed name and only if absent — so with a second
 * {@code @EnableRedisRepositories} in the context, and {@code spring-agent-integration-feishu}
 * contributes one, whichever is processed second has its configuration built as a bean and then
 * silently ignored. The annotations are read from the shared mapping context and cannot go missing
 * that way.
 */
@AutoConfiguration
@ConditionalOnPersistenceBackend(Type.REDIS)
@EnableRedisRepositories(basePackageClasses = RedisScheduledTaskRepo.class)
public class RedisPersistenceAutoConfiguration {}
