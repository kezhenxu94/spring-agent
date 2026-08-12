package me.kezhenxu94.springagent.dao.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.dao.models.McpServerConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface McpServerConfigRepo extends MongoRepository<McpServerConfig, String> {
  List<McpServerConfig> findByOwnerId(final String ownerId);

  Optional<McpServerConfig> findByOwnerIdAndName(final String ownerId, final String name);

  boolean existsByOwnerIdAndName(final String ownerId, final String name);

  void deleteByOwnerIdAndName(final String ownerId, final String name);

  /** Servers shared (directly or via a chat) with any of the given identifiers. */
  List<McpServerConfig> findBySharedWithIn(final Collection<String> identifiers);

  /** Servers owned by {@code ownerId}, plus any shared with one of {@code identifiers}. */
  @Query("{ '$or': [ { 'ownerId': ?0 }, { 'sharedWith': { '$in': ?1 } } ] }")
  List<McpServerConfig> findAccessibleTo(
      final String ownerId, final Collection<String> identifiers);
}
