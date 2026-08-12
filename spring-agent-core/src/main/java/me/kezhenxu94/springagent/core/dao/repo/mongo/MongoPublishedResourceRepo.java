package me.kezhenxu94.springagent.core.dao.repo.mongo;

import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** MongoDB implementation, registered only when {@code app.persistence.type} is {@code mongodb}. */
public interface MongoPublishedResourceRepo
    extends PublishedResourceRepo, MongoRepository<PublishedResource, String> {}
