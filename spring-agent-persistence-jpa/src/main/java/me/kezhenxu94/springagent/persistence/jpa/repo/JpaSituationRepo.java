package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The JPA implementation, registered when this module is the persistence backend in play. Every
 * method of the contract derives, so there is nothing to write.
 */
public interface JpaSituationRepo extends SituationRepo, JpaRepository<Situation, String> {}
