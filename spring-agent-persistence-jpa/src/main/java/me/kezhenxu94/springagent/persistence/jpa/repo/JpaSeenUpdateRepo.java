package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaSeenUpdateRepo extends SeenUpdateRepo, JpaRepository<SeenUpdate, String> {}
