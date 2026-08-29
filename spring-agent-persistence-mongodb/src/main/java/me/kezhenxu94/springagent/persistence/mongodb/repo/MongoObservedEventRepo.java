package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoObservedEventRepo
    extends ObservedEventRepo, MongoRepository<ObservedEvent, String> {}
