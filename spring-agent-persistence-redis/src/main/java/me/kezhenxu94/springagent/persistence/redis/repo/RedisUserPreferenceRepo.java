package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * Redis implementation, registered when this module is the persistence backend in play. Nothing to
 * add: the contract is save, findById and deleteById, all of which {@link CrudRepository} already
 * declares with the right signatures.
 */
public interface RedisUserPreferenceRepo
    extends UserPreferenceRepo, CrudRepository<UserPreference, String> {}
