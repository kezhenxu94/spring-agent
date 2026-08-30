package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>{@code findByStatus} and {@code findByUserIdAndStatus} derive: both properties are indexed, so
 * the second is an intersection of two Redis sets. {@code updateStatus} and {@code
 * incrementRunCount} cannot — Spring Data Redis has no annotation for a partial update the way JPA
 * and MongoDB do — and come from {@link ScheduledTaskPartialUpdate}.
 */
public interface RedisScheduledTaskRepo
    extends ScheduledTaskRepo, ScheduledTaskPartialUpdate, CrudRepository<ScheduledTask, String> {}
