package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Carries the mapping annotations of both persistence backends, since {@code app.persistence.type}
 * chooses between them at runtime. Keeping one model rather than a JPA entity plus a mapper is
 * deliberate: these are anemic records, and the duplicate set would be pure overhead. The JPA
 * annotations are inert under MongoDB and vice versa.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ScheduledTask.COLLECTION_NAME)
@Entity
@Table(name = ScheduledTask.COLLECTION_NAME)
public class ScheduledTask {
  public static final String COLLECTION_NAME = "bot_scheduled_tasks";

  // Both @Id annotations: org.springframework.data for MongoDB, jakarta.persistence for JPA. The id
  // is assigned by the application, so there is no generation strategy on either side.
  @Id @jakarta.persistence.Id private String id;
  private String userId;
  private String chatId;
  private String chatType;
  private String rootMessageId;

  // The prompt to run; longer than the default varchar a JPA schema would otherwise generate.
  @Column(length = 8192)
  private String taskText;

  private String cronExpression;
  private Instant scheduledAt;
  private Instant expiresAt;

  // STRING so the stored value matches what MongoDB writes, and so the column stays readable and
  // stable if the enum is ever reordered.
  @Enumerated(EnumType.STRING)
  private Status status;

  public enum Status {
    ACTIVE,
    CANCELLED,
    COMPLETED,
    FAILED
  }
}
