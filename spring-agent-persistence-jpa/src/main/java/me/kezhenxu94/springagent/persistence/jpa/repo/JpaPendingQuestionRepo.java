package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaPendingQuestionRepo
    extends PendingQuestionRepo, JpaRepository<PendingQuestion, String> {

  @Override
  // clearAutomatically so a row already in the persistence context does not shadow the new status
  // on a later read; the callers hold detached copies and re-read through the repository.
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update PendingQuestion q set q.status = :status where q.id = :id")
  void updateStatus(String id, PendingQuestion.Status status);
}
