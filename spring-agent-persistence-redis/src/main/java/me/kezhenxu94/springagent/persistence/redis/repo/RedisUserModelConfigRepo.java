package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import org.springframework.data.repository.CrudRepository;

/** Redis implementation, registered when this module is the persistence backend in play. */
public interface RedisUserModelConfigRepo
    extends UserModelConfigRepo, CrudRepository<UserModelConfig, String> {

  /** A default method rather than a derived query; see {@link RedisShellCredentialRepo}. */
  @Override
  default void deleteByOwnerIdAndName(final String ownerId, final String name) {
    findByOwnerIdAndName(ownerId, name).ifPresent(config -> deleteById(config.id()));
  }
}
