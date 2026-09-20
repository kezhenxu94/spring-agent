package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Jpa implementation, registered when this module is the persistence backend in play. Nothing to
 * add: the contract is save, findById and deleteById, all of which {@link JpaRepository} already
 * declares with the right signatures.
 */
public interface JpaUserPreferenceRepo
    extends UserPreferenceRepo, JpaRepository<UserPreference, String> {}
