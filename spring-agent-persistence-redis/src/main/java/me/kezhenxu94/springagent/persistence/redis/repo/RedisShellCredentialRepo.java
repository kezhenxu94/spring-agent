package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ShellCredential;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import org.springframework.data.repository.CrudRepository;

/** Redis implementation, registered when this module is the persistence backend in play. */
public interface RedisShellCredentialRepo
    extends ShellCredentialRepo, CrudRepository<ShellCredential, String> {

  /**
   * A default method rather than a derived query: Spring Data Redis has no derived deletes at all —
   * {@code KeyValuePartTreeQuery} understands exists and count projections but has no delete branch
   * — so the alternative is a custom fragment for two lines. Going through {@code deleteById} also
   * keeps the secondary indexes correct, which is the part that would be easy to get wrong by hand.
   */
  @Override
  default void deleteByOwnerIdAndName(final String ownerId, final String name) {
    findByOwnerIdAndName(ownerId, name).ifPresent(credential -> deleteById(credential.id()));
  }
}
