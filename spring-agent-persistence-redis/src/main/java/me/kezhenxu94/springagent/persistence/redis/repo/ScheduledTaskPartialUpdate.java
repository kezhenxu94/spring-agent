package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * The methods of {@code ScheduledTaskRepo} that Spring Data Redis cannot generate: both write one
 * property of a task without touching the rest. Split into a fragment interface rather than written
 * as default methods because the implementation needs a collaborator injected, which a default
 * method has no way to reach.
 *
 * @see ScheduledTaskPartialUpdateImpl
 */
public interface ScheduledTaskPartialUpdate {

  void updateStatus(String id, ScheduledTask.Status status);

  void incrementRunCount(String id);
}
