package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected; the same shape as {@link PendingQuestionRepo}, for the same reasons.
 */
public interface PendingDeviceCallRepo {

  PendingDeviceCall save(PendingDeviceCall call);

  Optional<PendingDeviceCall> findById(String id);

  List<PendingDeviceCall> findByConversationIdAndStatus(
      String conversationId, PendingDeviceCall.Status status);

  /**
   * Sets the status of one row without loading it, for the same race {@link PendingQuestionRepo}
   * names.
   */
  void updateStatus(String id, PendingDeviceCall.Status status);
}
