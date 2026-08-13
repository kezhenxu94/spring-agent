package me.kezhenxu94.springagent.persistence.jdbc.repo;

import me.kezhenxu94.springagent.core.dao.models.ShellCredential;
import me.kezhenxu94.springagent.core.dao.repo.ShellCredentialRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaShellCredentialRepo
    extends ShellCredentialRepo, JpaRepository<ShellCredential, String> {

  @Override
  @Transactional
  void deleteByOwnerIdAndName(String ownerId, String name);
}
