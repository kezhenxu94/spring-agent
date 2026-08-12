package me.kezhenxu94.springagent.dao.repo;

import me.kezhenxu94.springagent.dao.models.PublishedResource;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PublishedResourceRepo extends MongoRepository<PublishedResource, String> {}
