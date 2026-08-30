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
 * How much of what the agent has to say about itself one person has already been shown.
 *
 * <p>A surface that greets people has to answer "what is new for <em>you</em>", and the only way to
 * answer it is to remember where each of them got to. The notes the agent ships are numbered, so
 * one number per person is the whole of the state: everything above it is unread, and a person
 * whose number is the highest there is has nothing waiting.
 *
 * <p>Kept here rather than in the surface that greets, because the surface has no persistence of
 * its own and because nothing about the record is Feishu's: any surface that wants to say the same
 * thing wants the same row, keyed by whatever it calls a person.
 *
 * <p>Carries the mapping annotations of every persistence backend, since {@code
 * app.persistence.type} chooses between them at runtime; see {@link ScheduledTask} for why one
 * model rather than an entity plus a mapper.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = SeenUpdate.COLLECTION_NAME)
@Entity
@Table(name = SeenUpdate.COLLECTION_NAME)
@RedisHash(SeenUpdate.COLLECTION_NAME)
public class SeenUpdate {
  public static final String COLLECTION_NAME = "bot_seen_updates";

  /**
   * The person, as the surface that greeted them names one — a Feishu open id today. Nothing is
   * queried by anything else, so there is no {@code @Indexed} here.
   */
  @Id @jakarta.persistence.Id private String id;

  /** The number of the last note they were shown. Everything above it is still unread. */
  private int version;

  /** When they were last shown one, kept so a deployment can see the feature working. */
  private Instant updatedAt;
}
