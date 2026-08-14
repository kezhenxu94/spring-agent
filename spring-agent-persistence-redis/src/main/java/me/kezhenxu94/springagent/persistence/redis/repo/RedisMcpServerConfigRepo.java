package me.kezhenxu94.springagent.persistence.redis.repo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>The two sharing queries are the interesting part, as they are on the other two backends: JPA
 * spells them as a join over a collection table and MongoDB as an {@code $or}, and Redis can do
 * neither. Its derived queries cover only equality on an indexed property — {@code
 * RedisQueryCreator} accepts {@code SIMPLE_PROPERTY}, {@code TRUE}, {@code FALSE}, {@code WITHIN}
 * and {@code NEAR}, and nothing else — so there is no {@code In} keyword to derive from.
 *
 * <p>They are written as default methods over {@link #findBySharedWith}, one indexed read per
 * identifier, rather than as a custom fragment reading the index sets directly. The index key
 * layout is Spring Data Redis's own business, and a fragment that hand-built {@code
 * mcp_servers:sharedWith:ou_x} would break silently the day it changed. The callers pass a user's
 * own id plus the chats they are in, so the collection is small.
 */
public interface RedisMcpServerConfigRepo
    extends McpServerConfigRepo, CrudRepository<McpServerConfig, String> {

  /**
   * Servers shared with one identifier. Derivable because {@code sharedWith} is indexed
   * element-wise — see {@code SpringAgentIndexConfiguration} — so this is a set lookup rather than
   * a scan.
   */
  List<McpServerConfig> findBySharedWith(String identifier);

  @Override
  default List<McpServerConfig> findBySharedWithIn(final Collection<String> identifiers) {
    return distinctById(identifiers.stream().map(this::findBySharedWith).flatMap(List::stream));
  }

  @Override
  default List<McpServerConfig> findAccessibleTo(
      final String ownerId, final Collection<String> identifiers) {
    // The union the other two backends express as an $or and a left join. De-duplicated because a
    // server owned by ownerId may also be shared with one of the identifiers, and because one
    // server may be shared with several of them.
    return distinctById(
        Stream.concat(findByOwnerId(ownerId).stream(), findBySharedWithIn(identifiers).stream()));
  }

  @Override
  default void deleteByOwnerIdAndName(final String ownerId, final String name) {
    findByOwnerIdAndName(ownerId, name).ifPresent(config -> deleteById(config.id()));
  }

  /**
   * By id rather than by object: {@code McpServerConfig} is mutable and its equality is Lombok's.
   */
  private static List<McpServerConfig> distinctById(final Stream<McpServerConfig> configs) {
    final var byId = new LinkedHashMap<String, McpServerConfig>();
    configs.forEach(config -> byId.putIfAbsent(config.id(), config));
    return List.copyOf(byId.values());
  }
}
