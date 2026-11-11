package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;

/**
 * The one method of {@code PendingDeviceCallRepo} that Spring Data Redis cannot generate; see
 * {@link PendingQuestionStatusUpdate}.
 */
public interface PendingDeviceCallStatusUpdate {

  void updateStatus(String id, PendingDeviceCall.Status status);
}
