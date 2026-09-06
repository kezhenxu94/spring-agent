package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import org.springframework.data.repository.CrudRepository;

/** The Redis implementation, registered when this module is the persistence backend in play. */
public interface RedisObservedEventRepo
    extends ObservedEventRepo, CrudRepository<ObservedEvent, String> {

  /**
   * Written out rather than derived, like {@code RedisMcpServerConfigRepo.deleteByOwnerIdAndName}:
   * {@code RedisQueryCreator} builds finders over an indexed property and nothing else, so there is
   * no delete to derive from a part tree here.
   *
   * <p>The read it is built on is bounded by {@code app.events.max-events-per-situation}, which is
   * what makes loading a situation's observations in order to delete them affordable.
   */
  @Override
  default void deleteBySituationId(final String situationId) {
    findBySituationId(situationId).forEach(event -> deleteById(event.id()));
  }
}
