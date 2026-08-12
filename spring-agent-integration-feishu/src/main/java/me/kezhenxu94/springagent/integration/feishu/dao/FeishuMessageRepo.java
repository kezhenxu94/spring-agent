package me.kezhenxu94.springagent.integration.feishu.dao;

/**
 * Backend-neutral contract; see {@code ScheduledTaskRepo}.
 *
 * <p>{@code findOneByMessageRootId} used to be declared here and was never called, so it is not
 * carried over rather than being implemented twice.
 */
public interface FeishuMessageRepo {

  FeishuMessage save(FeishuMessage message);

  boolean existsById(String id);

  void updateStatus(String id, FeishuMessage.Status status);
}
