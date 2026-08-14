package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * The one method of {@code ScheduledTaskRepo} that Spring Data Redis cannot generate. Split into a
 * fragment interface rather than written as a default method because the implementation needs a
 * collaborator injected, which a default method has no way to reach.
 *
 * @see ScheduledTaskStatusUpdateImpl
 */
public interface ScheduledTaskStatusUpdate {

  void updateStatus(String id, ScheduledTask.Status status);
}
