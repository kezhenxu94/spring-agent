package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaPendingDeviceCallRepo
    extends PendingDeviceCallRepo, JpaRepository<PendingDeviceCall, String> {

  @Override
  // clearAutomatically for the reason JpaPendingQuestionRepo gives.
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update PendingDeviceCall c set c.status = :status where c.id = :id")
  void updateStatus(String id, PendingDeviceCall.Status status);
}
