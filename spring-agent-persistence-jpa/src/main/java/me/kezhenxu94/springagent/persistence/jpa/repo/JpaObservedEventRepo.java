package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaObservedEventRepo
    extends ObservedEventRepo, JpaRepository<ObservedEvent, String> {}
