package me.kezhenxu94.springagent.dao.models;

import java.time.Instant;
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
@Document(collection = ScheduledTask.COLLECTION_NAME)
public class ScheduledTask {
  public static final String COLLECTION_NAME = "bot_scheduled_tasks";

  @Id private String id;
  private String userId;
  private String chatId;
  private String chatType;
  private String rootMessageId;
  private String taskText;
  private String cronExpression;
  private Instant scheduledAt;
  private Instant expiresAt;
  private Status status;

  public enum Status {
    ACTIVE,
    CANCELLED,
    COMPLETED,
    FAILED
  }
}
