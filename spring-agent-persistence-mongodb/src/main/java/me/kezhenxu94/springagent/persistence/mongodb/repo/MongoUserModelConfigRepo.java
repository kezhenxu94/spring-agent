package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoUserModelConfigRepo
    extends UserModelConfigRepo, MongoRepository<UserModelConfig, String> {}
