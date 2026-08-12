package me.kezhenxu94.springagent.core.dao.repo.mongo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/**
 * The MongoDB implementation, registered only when {@code app.persistence.type} is {@code mongodb}.
 */
public interface MongoScheduledTaskRepo
    extends ScheduledTaskRepo, MongoRepository<ScheduledTask, String> {

  @Override
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'status': ?1 } }")
  void updateStatus(String id, ScheduledTask.Status status);
}
