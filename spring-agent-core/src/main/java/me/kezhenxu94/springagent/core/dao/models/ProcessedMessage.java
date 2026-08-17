package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.redis.core.RedisHash;

/**
 * A message that has already been taken up, so that being handed it a second time does not answer
 * it a second time.
 *
 * <p>Every surface that receives messages from somewhere else can be handed the same one twice: a
 * channel that has not heard back in time concludes its event was never delivered and sends it
 * again, and a reconnecting long-lived connection can replay one. What makes that visible to the
 * user is that a run is not cheap to start, so the second copy arrives while the first is still
 * working and both answer.
 *
 * <p>The claim has to be shared rather than held in a replica's heap, because a redelivery is free
 * to arrive at a different replica than the one still working on the first copy.
 *
 * <p>Carries the mapping annotations of every persistence backend, since {@code
 * app.persistence.type} chooses between them at runtime; see {@link ScheduledTask} for why one
 * model rather than an entity plus a mapper.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ProcessedMessage.COLLECTION_NAME)
@Entity
@Table(name = ProcessedMessage.COLLECTION_NAME)
@RedisHash(ProcessedMessage.COLLECTION_NAME)
public class ProcessedMessage {
  public static final String COLLECTION_NAME = "bot_processed_messages";

  /**
   * The channel's own id for the message. Nothing is ever queried by anything else, so there is no
   * {@code @Indexed} here — the id is the whole of the record's meaning.
   */
  @Id @jakarta.persistence.Id private String id;

  /** When it was claimed, which is what each backend expires the claim against. */
  private Instant createdAt;
}
