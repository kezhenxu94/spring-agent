package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ShellCredential;

/** Backend-neutral contract; see {@link ScheduledTaskRepo}. */
public interface ShellCredentialRepo {

  ShellCredential save(ShellCredential credential);

  List<ShellCredential> findByOwnerId(String ownerId);

  Optional<ShellCredential> findByOwnerIdAndName(String ownerId, String name);

  void deleteByOwnerIdAndName(String ownerId, String name);
}
