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
 * Carries the mapping annotations of every persistence backend, since {@code app.persistence.type}
 * chooses between them at runtime. Keeping one model rather than a JPA entity plus a mapper is
 * deliberate: these are anemic records, and the duplicate set would be pure overhead. Each
 * backend's annotations are inert under the others.
 *
 * <p>Redis is the one whose annotations carry a meaning the other two do not. It has no query
 * planner, so {@code @Indexed} is not a tuning decision but the definition of which queries can be
 * served at all: a property without it cannot be filtered on. Every {@code @Indexed} below is read
 * by a method on the matching {@code dao.repo} contract, and each one is a Redis set that every
 * write has to maintain, so nothing else carries it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ScheduledTask.COLLECTION_NAME)
@Entity
@Table(name = ScheduledTask.COLLECTION_NAME)
@RedisHash(ScheduledTask.COLLECTION_NAME)
public class ScheduledTask {
  public static final String COLLECTION_NAME = "bot_scheduled_tasks";

  // Both @Id annotations: org.springframework.data for MongoDB, jakarta.persistence for JPA. The id
  // is assigned by the application, so there is no generation strategy on either side. Redis uses
  // the org.springframework.data one too.
  @Id @jakarta.persistence.Id private String id;

  // findByUserIdAndStatus.
  @Indexed private String userId;

  private String chatId;
  private String chatType;
  private String rootMessageId;

  // The scopes the task was created in, stored so a firing runs with the same reach as the
  // conversation that asked for it: without them a firing sees only the creator's own home and
  // knowledge, and a task created in a group chat or under a tenant loses the very files and notes
  // it was written to work on. Blank where the integration has no such concept.
  private String groupId;
  private String tenantId;

  // What the task is called: a short line somebody wrote to be read in a list, as against the
  // prompt below, which is written to be read by the model. They are different jobs — a prompt runs
  // to paragraphs and says how, a title says what — and before there was one, every list on every
  // surface showed the first however-many characters of the prompt and each of them showed a
  // different amount of the same preamble.
  //
  // Nullable, and read as "fall back to the prompt": the schema is ddl-auto with no migrations, so
  // this column arrives on a table that already has rows, and those tasks are still worth listing.
  private String title;

  // The prompt to run; longer than the default varchar a JPA schema would otherwise generate.
  @Column(length = 8192)
  private String taskText;

  private String cronExpression;
  private Instant scheduledAt;
  private Instant expiresAt;

  // When this task next fires, and the whole of what makes a firing survive a restart: the schedule
  // lives here rather than in a timer held in one replica's heap, so an occurrence that fell while
  // the process was down is not skipped, it is simply overdue. ScheduledTaskSweeper reads it, and
  // advancing it is how a replica wins the right to fire that occurrence — see
  // ScheduledTaskRepo#claimNextFireAt.
  //
  // Deliberately not @Indexed. On Redis @Indexed is exact-match only, so it could never serve the
  // `nextFireAt <= now` this is read with; it would be a set maintained on every write in exchange
  // for nothing. The sweeper selects on the indexed `status` and filters this in memory, which is
  // what SituationSweeper does with `evaluateAfter` and for the same reason.
  //
  // Nullable, and null means two different things the sweeper has to tell apart: a row that
  // predates this column (ddl-auto with no migrations, so it arrives null on every existing task,
  // and is simply absent on MongoDB and Redis) has never had one computed and is backfilled; a
  // one-off that has already run has no next occurrence and is retired. See
  // ScheduledTaskSweeper#needsFirstFireAt.
  private Instant nextFireAt;

  // Whether a firing runs unattended, out of sight of the thread the task was created in. A
  // background firing posts no answer of its own: what it did is in the log, and the user hears
  // about it only if the task itself sends a message. A foreground one, the default, streams into a
  // reply on that thread as any other run does.
  //
  // What it is for: a task that decides for itself whether anything is worth saying — "check X, and
  // only if Y send a summary to Z" — or one that already sends its own message. A reply for the
  // firing is a second message on top of those, and in the "otherwise do nothing" case it is the
  // only message, which is the opposite of what was asked for.
  //
  // Boxed, and read as null-means-false: the schema is ddl-auto with no migrations, so this column
  // arrives on a table that already has rows, and a primitive would make Hibernate declare it not
  // null and then read those rows' nulls into it.
  private Boolean background;

  // How many firings the task gets in total, null for a task nothing counts, and how many it has
  // had. The bound is enforced by ScheduledTaskService before each firing rather than by the model,
  // so "ten times" is exactly ten however the firings are spaced and whatever a run decides to do.
  //
  // Boxed, and read as null-means-zero, for the same reason as `background` above: the schema is
  // ddl-auto with no migrations, so these columns arrive on a table that already has rows.
  private Integer maxRuns;
  private Integer runCount;

  // STRING so the stored value matches what MongoDB writes, and so the column stays readable and
  // stable if the enum is ever reordered.
  //
  // Indexed for findByStatus and findByUserIdAndStatus. It is also the property the Redis backend
  // partially updates, which is what keeps this index correct without rewriting the whole task.
  @Enumerated(EnumType.STRING)
  @Indexed
  private Status status;

  public enum Status {
    ACTIVE,
    CANCELLED,
    COMPLETED,
    FAILED
  }
}
