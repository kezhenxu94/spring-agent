package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoScheduledTaskRepo
    extends ScheduledTaskRepo, MongoRepository<ScheduledTask, String> {

  @Override
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'status': ?1 } }")
  void updateStatus(String id, ScheduledTask.Status status);

  @Override
  @Query("{ '_id': ?0 }")
  // $inc treats a missing field as zero, so a task written before the field existed counts from
  // one.
  @Update("{ '$inc': { 'runCount': 1 } }")
  void incrementRunCount(String id);
}
