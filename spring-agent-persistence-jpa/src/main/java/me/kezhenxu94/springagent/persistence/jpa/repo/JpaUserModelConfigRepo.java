package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaUserModelConfigRepo
    extends UserModelConfigRepo, JpaRepository<UserModelConfig, String> {

  @Override
  @Transactional
  void deleteByOwnerIdAndName(String ownerId, String name);
}
