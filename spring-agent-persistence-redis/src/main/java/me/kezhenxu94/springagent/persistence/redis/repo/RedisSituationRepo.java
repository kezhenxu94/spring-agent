package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>Nothing to write here, which is the point of the contract having no partial update and no
 * predicate over a timestamp: {@code findByCorrelationKeyAndStatus} is an intersection of two
 * indexed sets, and {@code findByStatus} and {@code findByPhase} are single indexed reads. Those
 * are exactly the shapes {@code RedisQueryCreator} can build.
 */
public interface RedisSituationRepo extends SituationRepo, CrudRepository<Situation, String> {}
