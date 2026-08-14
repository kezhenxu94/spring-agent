package me.kezhenxu94.springagent.integration.feishu.dao.redis;

import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;

/**
 * The one method of {@code FeishuMessageRepo} that Spring Data Redis cannot generate, split into a
 * fragment because the implementation needs a collaborator injected.
 *
 * @see FeishuMessageStatusUpdateImpl
 */
public interface FeishuMessageStatusUpdate {

  void updateStatus(String id, FeishuMessage.Status status);
}
