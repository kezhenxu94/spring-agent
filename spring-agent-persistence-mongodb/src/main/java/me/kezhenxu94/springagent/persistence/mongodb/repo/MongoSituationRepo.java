package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * The MongoDB implementation, registered when this module is the persistence backend in play. Every
 * method of the contract derives, so there is nothing to write.
 */
public interface MongoSituationRepo extends SituationRepo, MongoRepository<Situation, String> {}
