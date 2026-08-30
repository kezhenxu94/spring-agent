package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoSeenUpdateRepo extends SeenUpdateRepo, MongoRepository<SeenUpdate, String> {}
