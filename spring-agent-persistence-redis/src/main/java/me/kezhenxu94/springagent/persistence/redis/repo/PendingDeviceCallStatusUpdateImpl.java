package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import org.springframework.data.redis.core.PartialUpdate;
import org.springframework.data.redis.core.RedisKeyValueTemplate;

/** Named {@code <Fragment>Impl} on purpose; see {@link PendingQuestionStatusUpdateImpl}. */
public class PendingDeviceCallStatusUpdateImpl implements PendingDeviceCallStatusUpdate {

  private final RedisKeyValueTemplate template;

  public PendingDeviceCallStatusUpdateImpl(final RedisKeyValueTemplate template) {
    this.template = template;
  }

  @Override
  public void updateStatus(final String id, final PendingDeviceCall.Status status) {
    template.update(
        PartialUpdate.newPartialUpdate(id, PendingDeviceCall.class).set("status", status));
  }
}
