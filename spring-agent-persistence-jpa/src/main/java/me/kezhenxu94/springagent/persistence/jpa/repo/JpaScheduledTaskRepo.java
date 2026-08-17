package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaScheduledTaskRepo
    extends ScheduledTaskRepo, JpaRepository<ScheduledTask, String> {

  @Override
  // clearAutomatically so a task already in the persistence context does not shadow the new status
  // on a later read; the callers hold detached copies and re-read through the repository.
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update ScheduledTask t set t.status = :status where t.id = :id")
  void updateStatus(String id, ScheduledTask.Status status);
}
