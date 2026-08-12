package me.kezhenxu94.springagent.integration.feishu.dao;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Dual-mapped for both persistence backends; see {@code ScheduledTask} for why.
 *
 * <p>The id is the Feishu message id, not a generated key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = FeishuMessage.COLLECTION_NAME)
@Entity
@Table(name = FeishuMessage.COLLECTION_NAME)
public class FeishuMessage {
  public static final String COLLECTION_NAME = "feishu_translate_messages";

  @Id @jakarta.persistence.Id private String id;

  private String messageRootId;
  private String responseCardId;

  @Enumerated(EnumType.STRING)
  private Status status;

  public enum Status {
    GENERATING,
    FAILED,
    CANCELLED,
    COMPLETED,
  }
}
