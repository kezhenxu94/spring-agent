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
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

/**
 * A tool the agent asked the person's device to run, which the device did not get to while the run
 * waited. The run has moved on — it was told the call now waits on the person — so the device's
 * result, whenever it comes, starts a fresh run in the same conversation, and everything needed for
 * that is written down here. {@link PendingQuestion} is the same arrangement for a question.
 *
 * <p>Carries the mapping annotations of every persistence backend; see {@link ScheduledTask}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = PendingDeviceCall.COLLECTION_NAME)
@Entity
@Table(name = PendingDeviceCall.COLLECTION_NAME)
@RedisHash(PendingDeviceCall.COLLECTION_NAME)
public class PendingDeviceCall {
  public static final String COLLECTION_NAME = "bot_pending_device_calls";

  /** The call's id, as the device read it in the run's journal. */
  @Id @jakarta.persistence.Id private String id;

  private String userId;
  private String chatId;

  // findByConversationIdAndStatus: what the conversation still waits on, and what a typed message
  // supersedes.
  @Indexed private String conversationId;

  private String rootMessageId;

  private String toolName;

  /** The arguments exactly as the model wrote them, for the device to run the call with. */
  @Column(length = 65536)
  private String input;

  // Indexed for findByConversationIdAndStatus, and the property the Redis backend partially
  // updates.
  @Enumerated(EnumType.STRING)
  @Indexed
  private Status status;

  private Instant createdAt;

  /** Checked when a result arrives rather than swept by a job, as for {@link PendingQuestion}. */
  private Instant expiresAt;

  public enum Status {
    PENDING,
    ANSWERED,
    /** A later message in the conversation moved it on, so a result must not fire a second run. */
    SUPERSEDED,
    EXPIRED
  }
}
