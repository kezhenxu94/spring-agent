package me.kezhenxu94.springagent.persistence.jpa.repo;

import java.time.Instant;
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

  @Override
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update ScheduledTask t set t.taskText = :taskText where t.id = :id")
  void updateTaskText(String id, String taskText);

  @Override
  @Modifying(clearAutomatically = true)
  @Transactional
  // coalesce because the column arrives null on every task that predates it, and null + 1 is null.
  @Query("update ScheduledTask t set t.runCount = coalesce(t.runCount, 0) + 1 where t.id = :id")
  void incrementRunCount(String id);

  // The two conditional writes behind claimNextFireAt and initNextFireAt. Declared returning int
  // and adapted below rather than declared boolean directly: a modifying query's row count is the
  // portable return type across Spring Data versions, and the adapter is one line.
  //
  // The predicate lives in the where clause, which is the whole point — the database decides who
  // wins. Two replicas issuing this for the same occurrence both run an update; exactly one of them
  // matches a row, because the first to commit has already moved nextFireAt off :expected.
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update ScheduledTask t set t.nextFireAt = :next"
          + " where t.id = :id and t.nextFireAt = :expected")
  int advanceNextFireAt(String id, Instant expected, Instant next);

  @Modifying(clearAutomatically = true)
  @Transactional
  @Query(
      "update ScheduledTask t set t.nextFireAt = :next"
          + " where t.id = :id and t.nextFireAt is null")
  int setFirstNextFireAt(String id, Instant next);

  @Override
  default boolean claimNextFireAt(final String id, final Instant expected, final Instant next) {
    return advanceNextFireAt(id, expected, next) > 0;
  }

  @Override
  default boolean initNextFireAt(final String id, final Instant next) {
    return setFirstNextFireAt(id, next) > 0;
  }
}
