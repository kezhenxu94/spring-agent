package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ScheduledTaskRepo extends MongoRepository<ScheduledTask, String> {
  List<ScheduledTask> findByUserIdAndStatus(String userId, ScheduledTask.Status status);

  List<ScheduledTask> findByStatus(ScheduledTask.Status status);
}
