package me.kezhenxu94.springagent.integration.feishu.dao.redis;

import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessageRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * Redis implementation, registered only when {@code app.persistence.type} is {@code redis}.
 *
 * <p>{@code save} and {@code existsById} come straight from {@link CrudRepository}; {@code
 * updateStatus} is a partial update, which Spring Data Redis has no annotation for, so it arrives
 * through {@link FeishuMessageStatusUpdate}.
 */
public interface RedisFeishuMessageRepo
    extends FeishuMessageRepo, FeishuMessageStatusUpdate, CrudRepository<FeishuMessage, String> {}
