package me.kezhenxu94.springagent.core.dao.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;

/** Backend-neutral contract; see {@link ScheduledTaskRepo}. */
public interface McpServerConfigRepo {

  McpServerConfig save(McpServerConfig config);

  List<McpServerConfig> findByOwnerId(String ownerId);

  Optional<McpServerConfig> findByOwnerIdAndName(String ownerId, String name);

  boolean existsByOwnerIdAndName(String ownerId, String name);

  void deleteByOwnerIdAndName(String ownerId, String name);

  /** Servers shared (directly or via a chat) with any of the given identifiers. */
  List<McpServerConfig> findBySharedWithIn(Collection<String> identifiers);

  /**
   * Servers owned by {@code ownerId}, plus any shared with one of {@code identifiers}. Not a
   * derived query on either backend, so each sub-interface spells it out in its own query language.
   */
  List<McpServerConfig> findAccessibleTo(String ownerId, Collection<String> identifiers);
}
