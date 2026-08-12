package me.kezhenxu94.springagent.integration.feishu.dao;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeishuMessageRepo extends MongoRepository<FeishuMessage, String> {
  Optional<FeishuMessage> findOneByMessageRootId(final String messageRootId);
}
