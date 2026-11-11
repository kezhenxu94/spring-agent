package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play. Built
 * like {@link RedisPendingQuestionRepo}: {@code updateStatus} comes from {@link
 * PendingDeviceCallStatusUpdate}.
 */
public interface RedisPendingDeviceCallRepo
    extends PendingDeviceCallRepo,
        PendingDeviceCallStatusUpdate,
        CrudRepository<PendingDeviceCall, String> {}
