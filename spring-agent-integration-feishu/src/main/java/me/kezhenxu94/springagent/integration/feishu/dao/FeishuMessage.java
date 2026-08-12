package me.kezhenxu94.springagent.integration.feishu.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = FeishuMessage.COLLECTION_NAME)
public class FeishuMessage {
  public static final String COLLECTION_NAME = "feishu_translate_messages";

  @Id private String id;

  private String messageRootId;
  private String responseCardId;

  private Status status;

  public enum Status {
    GENERATING,
    FAILED,
    CANCELLED,
    COMPLETED,
  }
}
