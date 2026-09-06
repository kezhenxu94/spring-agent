package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaObservedEventRepo
    extends ObservedEventRepo, JpaRepository<ObservedEvent, String> {

  // A derived delete is not transactional the way the inherited deleteById is, so without this it
  // throws TransactionRequiredException on the first retention pass. The same override, for the
  // same reason, as JpaMcpServerConfigRepo.deleteByOwnerIdAndName.
  @Override
  @Transactional
  void deleteBySituationId(String situationId);
}
