package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;

/** Backend-neutral contract; see {@link ScheduledTaskRepo}. */
public interface UserModelConfigRepo {

  UserModelConfig save(UserModelConfig config);

  List<UserModelConfig> findByOwnerId(String ownerId);

  Optional<UserModelConfig> findByOwnerIdAndName(String ownerId, String name);

  void deleteByOwnerIdAndName(String ownerId, String name);
}
