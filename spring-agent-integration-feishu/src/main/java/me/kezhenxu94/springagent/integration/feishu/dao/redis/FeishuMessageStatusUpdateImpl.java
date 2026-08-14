package me.kezhenxu94.springagent.integration.feishu.dao.redis;

import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import org.springframework.data.redis.core.PartialUpdate;
import org.springframework.data.redis.core.RedisKeyValueTemplate;

/**
 * The {@code Impl} suffix is how Spring Data finds this class from {@link
 * FeishuMessageStatusUpdate}; renaming it detaches the two and {@code updateStatus} starts failing
 * as an underivable query.
 *
 * <p>A partial update rather than a read-modify-write: a card's status changes while the stream
 * that owns the rest of the record is still writing to it.
 */
public class FeishuMessageStatusUpdateImpl implements FeishuMessageStatusUpdate {

  private final RedisKeyValueTemplate template;

  public FeishuMessageStatusUpdateImpl(final RedisKeyValueTemplate template) {
    this.template = template;
  }

  @Override
  public void updateStatus(final String id, final FeishuMessage.Status status) {
    template.update(PartialUpdate.newPartialUpdate(id, FeishuMessage.class).set("status", status));
  }
}
