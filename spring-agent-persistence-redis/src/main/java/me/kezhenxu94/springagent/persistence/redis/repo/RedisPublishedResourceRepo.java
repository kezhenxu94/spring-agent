package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * Redis implementation, registered when this module is the persistence backend in play. The only
 * one of the four with nothing to add: its contract is save, findById and deleteById, all of which
 * {@link CrudRepository} already declares with the right signatures.
 */
public interface RedisPublishedResourceRepo
    extends PublishedResourceRepo, CrudRepository<PublishedResource, String> {}
