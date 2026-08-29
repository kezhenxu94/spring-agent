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
 * Something that may be going on, assembled from many observations rather than from one — a
 * database that keeps timing out, an issue nobody has answered, a group chat filling up with the
 * same question. What the agent is woken up to think about.
 *
 * <p>This is the unit the expensive part is spent on, and the reason the cheap part exists. A
 * thousand alerts from one outage are a thousand {@link ObservedEvent} rows and one of these, so
 * the model is asked once. Correlation into a situation is arithmetic on {@code correlationKey} and
 * nothing else — no inference, or the layer that exists to avoid inference would need some.
 *
 * <p>See {@link ScheduledTask} for why one class carries every backend's mapping annotations, and
 * for what {@code @Indexed} means to the Redis backend in particular. The choice of two very plain
 * state fields is that constraint showing through: Redis secondary indexes serve equality and
 * nothing else, so there is no {@code status IN (...)} to be had, and a lifecycle expressed as one
 * wide enum could not be queried at all. {@link Status} answers "is this still a thing" and {@link
 * Phase} answers "what is happening to it", and every query this codebase needs is equality on one
 * of them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = Situation.COLLECTION_NAME)
@Entity
@Table(name = Situation.COLLECTION_NAME)
@RedisHash(Situation.COLLECTION_NAME)
public class Situation {
  public static final String COLLECTION_NAME = "bot_situations";

  @Id @jakarta.persistence.Id private String id;

  /** Which surface saw this, and so which settings under {@code app.events.sources} apply to it. */
  private String source;

  // findByCorrelationKeyAndStatus, which is how an arriving observation finds the situation it
  // belongs to. The one query on the ingest path, so the one that has to be an index everywhere.
  @Indexed private String correlationKey;

  private String title;

  // findByStatus, for the sweep that closes situations nothing has been heard about.
  @Enumerated(EnumType.STRING)
  @Indexed
  private Status status;

  // findByPhase, which is how the sweeper finds the situations owed an evaluation.
  @Enumerated(EnumType.STRING)
  @Indexed
  private Phase phase;

  /**
   * When this situation has been quiet long enough to be worth thinking about. Pushed further out
   * by every arriving observation, which is the debounce: an outage still emitting alerts has not
   * settled into anything worth an opinion yet.
   *
   * <p>Not queried on, and cannot be: no backend here serves a range predicate, Redis least of all.
   * The sweeper reads the situations in {@link Phase#AWAITING_EVALUATION} — equality, which every
   * backend does serve — and compares this in memory. The set of open situations is small enough
   * that this is the cheaper design as well as the only portable one.
   */
  private Instant evaluateAfter;

  private Instant firstSeenAt;

  /**
   * When the run of observations that has not been evaluated yet began.
   *
   * <p>What {@code max-debounce} is measured from, and so the reason a source emitting steadily is
   * still looked at. The debounce alone is a deadline every new observation pushes further out; a
   * cap on it has to be anchored to something that does not move, and the moment the situation
   * became due is that anchor.
   */
  private Instant awaitingSince;

  private Instant lastEventAt;
  private Instant lastEvaluatedAt;
  private Instant resolvedAt;

  /**
   * How many times this situation has been evaluated, and part of the {@code requestId} of each of
   * those runs so that two of them cannot collide in the agent's live-run map.
   *
   * <p>Boxed, like {@code ScheduledTask.background}: the schema is maintained by {@code ddl-auto:
   * update} with no migrations, so rows written before this field existed hold null, and null is
   * read as zero rather than crashing a sweep.
   */
  private Integer generation;

  /** How many observations have joined, including any past {@code max-events-per-situation}. */
  private Integer eventCount;

  /** What the agent concluded last time it looked, or null before it ever has. */
  @Enumerated(EnumType.STRING)
  private Decision decision;

  private String severity;

  private Double confidence;

  /**
   * The agent's own account of what it believes is happening, in its words, and what it did about
   * it. Handed back to it on the next evaluation in place of a conversation history — see {@code
   * SituationTriageScenario} for why a long-lived situation must not accumulate one.
   */
  @Column(length = 8192)
  private String assessment;

  /**
   * Why the last evaluation failed, where it did. A triage run is unattended and reports nowhere,
   * so without this a run that died would leave no trace anybody looks at.
   */
  @Column(length = 4096)
  private String lastError;

  /**
   * The identity a triage run assumes, resolved when the situation was created and kept so that a
   * restart fires the same run rather than one scoped to nothing.
   *
   * <p>{@code ScheduledTask} carries no group or tenant and its firings lose the workspace scoping
   * a chat run has; that is a bug not to repeat here, which is why all five are stored.
   */
  private String ownerUserId;

  private String chatId;
  private String chatType;
  private String groupId;
  private String tenantId;

  /** Whether this is still a live concern. Two values, because Redis cannot express {@code IN}. */
  public enum Status {
    OPEN,
    RESOLVED
  }

  /** What is happening to it, within {@link Status#OPEN}. */
  public enum Phase {
    /** Observations have arrived since the last evaluation; the debounce is running. */
    AWAITING_EVALUATION,
    /** A run is looking at it now. Nothing else may start one. */
    INVESTIGATING,
    /** Looked at, and waiting to see whether anything else happens. */
    MONITORING
  }

  /**
   * What the agent decided. Deliberately a small vocabulary shared by every source, so that a new
   * kind of event does not need a new word: an alert worth telling somebody about and a question
   * worth answering in a chat are both {@link #ACTED}, and the transport-specific part of that —
   * who was told, and how — is in the assessment and in whatever tool it called.
   */
  public enum Decision {
    /** Not worth anybody's attention. A transient failure, or a question already answered. */
    NO_ACTION,
    /** The agent said or did something itself. */
    ACTED,
    /** Beyond what the agent should settle on its own; a person needs to look. */
    ESCALATED
  }
}
