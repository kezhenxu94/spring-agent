package me.kezhenxu94.springagent.persistence.redis.repo;

import java.time.Instant;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * The methods of {@code ScheduledTaskRepo} that Spring Data Redis cannot generate: each writes one
 * property of a task without touching the rest, and the last two have to do so conditionally. Split
 * into a fragment interface rather than written as default methods because the implementation needs
 * collaborators injected, which a default method has no way to reach.
 *
 * @see ScheduledTaskPartialUpdateImpl
 */
public interface ScheduledTaskPartialUpdate {

  void updateStatus(String id, ScheduledTask.Status status);

  void incrementRunCount(String id);

  boolean claimNextFireAt(String id, Instant expected, Instant next);

  boolean initNextFireAt(String id, Instant next);
}
