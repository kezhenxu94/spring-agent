package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Column;
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
import org.springframework.data.redis.core.index.Indexed;

/**
 * One observation as it was recorded: the evidence a {@link Situation} is assembled from and the
 * detail the agent is shown when the situation's summary is not enough.
 *
 * <p>Written before anything else happens, which is what makes the receiving end durable without a
 * message broker: an event that reached storage will be reasoned about even if the process dies
 * before the debounce expires, because the sweep that finds it works from the database and not from
 * anything held in memory.
 *
 * <p>The id is the transport's delivery key rather than a generated one. That is the whole
 * deduplication story for the table — a redelivery writes the row it already wrote — and it is why
 * the claim taken in front of it can be released safely: nothing downstream depends on the row
 * being new.
 *
 * <p>See {@link ScheduledTask} for why one class carries every backend's mapping annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ObservedEvent.COLLECTION_NAME)
@Entity
@Table(name = ObservedEvent.COLLECTION_NAME)
@RedisHash(ObservedEvent.COLLECTION_NAME)
public class ObservedEvent {
  public static final String COLLECTION_NAME = "bot_observed_events";

  /** The transport's delivery key, so that recording a redelivery overwrites rather than adds. */
  @Id @jakarta.persistence.Id private String id;

  // findBySituationId, for building the evidence the agent is shown.
  @Indexed private String situationId;

  private String source;
  private String kind;

  /** What this observation says, in one line, for the evidence list in the prompt. */
  @Column(length = 1024)
  private String summary;

  /**
   * The raw payload as it arrived. Written by whoever caused the event and therefore untrusted: it
   * reaches the model only inside a prompt that says so.
   */
  @Column(length = 131072)
  private String payloadJson;

  private Instant observedAt;
}
