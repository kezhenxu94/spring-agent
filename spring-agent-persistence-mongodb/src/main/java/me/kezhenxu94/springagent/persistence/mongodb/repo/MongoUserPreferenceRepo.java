package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Mongo implementation, registered when this module is the persistence backend in play. Nothing to
 * add: the contract is save, findById and deleteById, all of which {@link MongoRepository} already
 * declares with the right signatures.
 */
public interface MongoUserPreferenceRepo
    extends UserPreferenceRepo, MongoRepository<UserPreference, String> {}
