package me.kezhenxu94.springagent.dao.repo;

import java.util.Optional;
import me.kezhenxu94.springagent.dao.models.FeishuMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeishuMessageRepo extends MongoRepository<FeishuMessage, String> {
  Optional<FeishuMessage> findOneByMessageRootId(final String messageRootId);
}
