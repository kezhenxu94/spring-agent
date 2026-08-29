package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import org.springframework.data.repository.CrudRepository;

/** The Redis implementation, registered when this module is the persistence backend in play. */
public interface RedisObservedEventRepo
    extends ObservedEventRepo, CrudRepository<ObservedEvent, String> {}
