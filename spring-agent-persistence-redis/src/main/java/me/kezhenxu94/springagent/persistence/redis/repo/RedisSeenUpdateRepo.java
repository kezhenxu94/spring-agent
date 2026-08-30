package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * Redis implementation, registered when this module is the persistence backend in play. Nothing to
 * add: the contract is save and findById, both of which {@link CrudRepository} already declares
 * with the right signatures.
 */
public interface RedisSeenUpdateRepo extends SeenUpdateRepo, CrudRepository<SeenUpdate, String> {}
