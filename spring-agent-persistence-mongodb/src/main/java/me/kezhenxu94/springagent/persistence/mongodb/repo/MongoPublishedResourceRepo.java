package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoPublishedResourceRepo
    extends PublishedResourceRepo, MongoRepository<PublishedResource, String> {}
