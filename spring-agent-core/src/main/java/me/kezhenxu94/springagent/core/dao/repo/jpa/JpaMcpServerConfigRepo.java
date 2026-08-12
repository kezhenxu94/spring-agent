package me.kezhenxu94.springagent.core.dao.repo.jpa;

import java.util.Collection;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** JPA implementation, registered only when {@code app.persistence.type} is {@code jdbc}. */
public interface JpaMcpServerConfigRepo
    extends McpServerConfigRepo, JpaRepository<McpServerConfig, String> {

  // A join over the sharedWith collection table. distinct because a server shared with several of
  // the given identifiers would otherwise be returned once per match.
  @Override
  @Query("select distinct c from McpServerConfig c join c.sharedWith s where s in :identifiers")
  List<McpServerConfig> findBySharedWithIn(Collection<String> identifiers);

  // A left join, unlike findBySharedWithIn: a server owned by ownerId must match even when its
  // sharedWith list is empty, which an inner join would drop.
  @Override
  @Query(
      "select distinct c from McpServerConfig c left join c.sharedWith s"
          + " where c.ownerId = :ownerId or s in :identifiers")
  List<McpServerConfig> findAccessibleTo(String ownerId, Collection<String> identifiers);

  @Override
  @Transactional
  void deleteByOwnerIdAndName(String ownerId, String name);
}
