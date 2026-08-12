package me.kezhenxu94.springagent.core.dao.repo.mongo;

import java.util.Collection;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** MongoDB implementation, registered only when {@code app.persistence.type} is {@code mongodb}. */
public interface MongoMcpServerConfigRepo
    extends McpServerConfigRepo, MongoRepository<McpServerConfig, String> {

  @Override
  @Query("{ '$or': [ { 'ownerId': ?0 }, { 'sharedWith': { '$in': ?1 } } ] }")
  List<McpServerConfig> findAccessibleTo(String ownerId, Collection<String> identifiers);
}
