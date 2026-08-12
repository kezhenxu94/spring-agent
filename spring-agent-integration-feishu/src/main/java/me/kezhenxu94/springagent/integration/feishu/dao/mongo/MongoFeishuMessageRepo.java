package me.kezhenxu94.springagent.integration.feishu.dao.mongo;

import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessageRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** MongoDB implementation, registered only when {@code app.persistence.type} is {@code mongodb}. */
public interface MongoFeishuMessageRepo
    extends FeishuMessageRepo, MongoRepository<FeishuMessage, String> {

  @Override
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'status': ?1 } }")
  void updateStatus(String id, FeishuMessage.Status status);
}
