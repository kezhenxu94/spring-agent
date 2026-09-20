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
 * What one person has said they want, for the decisions this runtime would otherwise make for them.
 *
 * <p>One row per person rather than one per setting, and a typed field per setting rather than a
 * key and a string. The row is only ever read whole — a run asks "what does this person want" once,
 * at the top — so a table of name/value pairs would be several reads to answer one question, and
 * would trade a field somebody can document for a string nobody can spell-check. Adding a
 * preference is a field here, which on {@code ddl-auto: update} is a column that appears, and
 * nothing in any of the three backend modules changes.
 *
 * <p>Distinct from {@code MemoryStore}, and the line matters: a memory is prose the agent wrote
 * about a person and reads back as context, where this is configuration the person set and the
 * runtime obeys without the model being consulted. A preference that only worked when the model
 * remembered to honour it would not be one.
 *
 * <p>Carries the mapping annotations of every persistence backend, since {@code
 * app.persistence.type} chooses between them at runtime; see {@link ScheduledTask} for why one
 * model rather than an entity plus a mapper.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = UserPreference.COLLECTION_NAME)
@Entity
@Table(name = UserPreference.COLLECTION_NAME)
@RedisHash(UserPreference.COLLECTION_NAME)
public class UserPreference {
  public static final String COLLECTION_NAME = "bot_user_preferences";

  /**
   * The person, as the surface that served them names one. Nothing is queried by anything else — a
   * preference is always read for the one person a run belongs to — so there is no {@code @Indexed}
   * here, which on Redis is also the statement that nothing may filter by anything else.
   */
  @Id @jakarta.persistence.Id private String id;

  /**
   * The scenario their runs start in when they have not said otherwise, by one of its {@code
   * memoNames()} — {@code kb}, {@code mini}, {@code full}. Null or blank where they have expressed
   * no preference, which is everybody until they say so.
   *
   * <p>Stored as the memo rather than as the enum constant on purpose. It is the word the person
   * typed and the word a tool reports back to them, so storing anything else would mean a
   * translation in both directions and a row that reads as nothing they recognise. It also keeps
   * the column honest across a deployment that adds a scenario of its own: an unknown word resolves
   * to nothing and the run falls back, where a stale enum name would fail to deserialize.
   */
  private String scenario;

  /** When they last changed one, kept so a person can be told what they set and when. */
  private Instant updatedAt;
}
