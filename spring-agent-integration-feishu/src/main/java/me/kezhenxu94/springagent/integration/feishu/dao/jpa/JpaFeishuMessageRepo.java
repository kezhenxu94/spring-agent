package me.kezhenxu94.springagent.integration.feishu.dao.jpa;

import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessageRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** JPA implementation, registered only when {@code app.persistence.type} is {@code jdbc}. */
public interface JpaFeishuMessageRepo
    extends FeishuMessageRepo, JpaRepository<FeishuMessage, String> {

  @Override
  @Modifying(clearAutomatically = true)
  @Transactional
  @Query("update FeishuMessage m set m.status = :status where m.id = :id")
  void updateStatus(String id, FeishuMessage.Status status);
}
