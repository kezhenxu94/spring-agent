package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared, and the method names have to remain
 * valid derived queries on every backend — see {@link ScheduledTaskRepo}.
 */
public interface PendingQuestionRepo {

  PendingQuestion save(PendingQuestion question);

  Optional<PendingQuestion> findById(String id);

  /**
   * Used to find what is still unanswered in a conversation, so that a typed reply can supersede a
   * form nobody got around to submitting.
   */
  List<PendingQuestion> findByConversationIdAndStatus(
      String conversationId, PendingQuestion.Status status);

  /**
   * Sets the status of one row without loading it. The caller is racing every other way the same
   * questions could be answered, and must not write the rest of the row back over the winner.
   */
  void updateStatus(String id, PendingQuestion.Status status);
}
