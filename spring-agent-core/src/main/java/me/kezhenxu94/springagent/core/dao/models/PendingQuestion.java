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
 * A set of questions the agent asked and is waiting to hear back on. The run that asked them is
 * long over by the time an answer arrives — the agent never blocks on a person — so everything
 * needed to start a fresh run in the same conversation has to be written down here.
 *
 * <p>Carries the mapping annotations of every persistence backend, since {@code
 * app.persistence.type} chooses between them at runtime; see {@link ScheduledTask} for why one
 * model rather than an entity plus a mapper, and for what {@code @Indexed} means to Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = PendingQuestion.COLLECTION_NAME)
@Entity
@Table(name = PendingQuestion.COLLECTION_NAME)
@RedisHash(PendingQuestion.COLLECTION_NAME)
public class PendingQuestion {
  public static final String COLLECTION_NAME = "bot_pending_questions";

  @Id @jakarta.persistence.Id private String id;

  private String userId;
  private String chatId;
  private String chatType;

  // findByConversationIdAndStatus: a message arriving in the conversation supersedes whatever is
  // still unanswered in it.
  @Indexed private String conversationId;

  private String rootMessageId;

  /**
   * The cardkit id of the card the form was inserted into — not the id of the message that card was
   * sent as. The answer arrives after the run, and its card updater, are gone, so this is what lets
   * the callback still reach that card.
   */
  private String cardId;

  /**
   * The questions as the model phrased them, serialized. Read back when the answers arrive, to turn
   * option indexes into the labels the model will recognise. Longer than the default varchar a JPA
   * schema would otherwise generate.
   */
  @Column(length = 8192)
  private String questionsJson;

  // Indexed for findByConversationIdAndStatus, and the property the Redis backend partially
  // updates, which is what keeps this index correct without rewriting the whole row.
  @Enumerated(EnumType.STRING)
  @Indexed
  private Status status;

  private Instant createdAt;

  /**
   * When the questions stop being answerable. Checked when an answer arrives rather than swept by a
   * job: nothing else needs to happen at that moment, so a scheduler would only be a second thing
   * to keep running.
   *
   * <p>Bounded from above by Feishu regardless of what it is set to — a card entity expires 14 days
   * after it is created, and a form on a dead card cannot be answered.
   */
  private Instant expiresAt;

  public enum Status {
    PENDING,
    ANSWERED,
    /**
     * A later message in the conversation answered them, so the form must not fire a second run.
     */
    SUPERSEDED,
    EXPIRED
  }
}
