package me.kezhenxu94.springagent.core.dao.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared — deliberately narrower than a {@code
 * CrudRepository}, so that adding a backend means implementing a known, small surface.
 *
 * <p>Spring Data generates the implementation from the sub-interface of the selected backend, so
 * the method names here have to remain valid derived queries on both sides.
 */
public interface ScheduledTaskRepo {

  ScheduledTask save(ScheduledTask task);

  Optional<ScheduledTask> findById(String id);

  List<ScheduledTask> findByStatus(ScheduledTask.Status status);

  List<ScheduledTask> findByUserIdAndStatus(String userId, ScheduledTask.Status status);

  /**
   * Sets the status of one task without loading it, which is what the callers actually want — they
   * hold a stale copy and must not write the rest of its fields back over a concurrent update.
   */
  void updateStatus(String id, ScheduledTask.Status status);

  /**
   * Rewrites the prompt a task will run, without touching the rest of it.
   *
   * <p>Partial for the reason {@link #updateStatus} is, and more sharply: the sweeper owns {@code
   * runCount} and {@code nextFireAt} and is writing them from another thread — or another replica —
   * while somebody is editing the text here. Saving a whole task read before the edit would put a
   * stale next occurrence back and fire the task again at a time it has already passed.
   *
   * <p>Only the text. When a task fires is the agent's to decide through its tools, which is where
   * the rules about what a schedule may be live; there is no second set of them here.
   */
  void updateTaskText(String id, String taskText);

  /**
   * Counts one firing of the task, for the sake of {@code maxRuns}. Partial for the same reason
   * {@link #updateStatus} is: the caller is the sweeper, holding the copy of the task it read at
   * the start of this sweep, and writing that back would undo whatever has been edited since.
   */
  void incrementRunCount(String id);

  /**
   * Moves a task on from the occurrence at {@code expected} to the one at {@code next}, and says
   * whether this caller is the one that did it.
   *
   * <p>This is how two replicas sweeping one database avoid both firing the same occurrence, and
   * the return value <em>is</em> the concurrency control rather than a convenience: an
   * implementation that reads {@code nextFireAt} and then writes it lets both callers through,
   * which is the case this exists for. It has to be one atomic conditional write — a
   * {@code @Modifying} update with the predicate in its {@code where} clause, a Mongo update with
   * the expected value in its filter, or on Redis a {@code SET NX} on a key naming the occurrence.
   *
   * <p>Preferred to claiming the occurrence in {@link ProcessedMessageRepo} for two reasons. It
   * stores nothing extra — the row that has to be written is written, and a claim record that never
   * expires would be one small permanent record per firing, which for a five-minutely task is a
   * hundred thousand a year, in memory on the Redis backend. And it fails safe: a replica that dies
   * between winning and firing loses that one occurrence and the task recovers at the next, whereas
   * a claim taken and never acted on leaves the task looking due for ever, refusing every later
   * claim, dead with nothing in the log to say so.
   *
   * <p>{@code next} may be null, meaning there is no further occurrence — a one-off that has now
   * fired, or a cron expression that yields nothing more. A backend has to be able to write that as
   * an absent value rather than skipping the write.
   *
   * @param expected the value the caller read, which the stored one must still equal
   * @return true when this caller moved the task on, false when another already had
   */
  boolean claimNextFireAt(String id, Instant expected, Instant next);

  /**
   * Gives a task with no next occurrence recorded its first, and says whether this caller is the
   * one that did it. The same atomicity requirement as {@link #claimNextFireAt}, against the
   * absence of a value rather than a particular one.
   *
   * <p>Separate from {@link #claimNextFireAt} with a null {@code expected} because "is null" is a
   * different predicate in every backend — {@code is null} in JPQL, an explicit null in a Mongo
   * filter, a missing hash field on Redis — and folding the two into one query means a disjunction
   * whose parameter typing is the sort of thing that works on one database and fails on another.
   *
   * <p>What needs it: a task written before {@code nextFireAt} existed. The schema is {@code
   * ddl-auto} with no migrations, so the column arrives null on every existing row, and the sweeper
   * backfills them as it meets them rather than a startup migration doing it — which would also
   * have to cope with an older replica still writing such rows during a rolling upgrade.
   */
  boolean initNextFireAt(String id, Instant next);
}
