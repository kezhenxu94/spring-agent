package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.Situation;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared — see {@link ScheduledTaskRepo}.
 *
 * <p>No partial-update method, unlike {@link ScheduledTaskRepo#updateStatus}, and that is a
 * deliberate saving rather than an oversight. One costs the Redis backend a fragment interface plus
 * a class whose name Spring Data matches textually, and it exists there because those callers hold
 * a stale copy they must not write back. Every caller here has just read the situation it is about
 * to change — the ingest path reads it to append to it, the sweeper reads it to claim it — so a
 * whole-object {@code save} is both correct and the smaller surface to implement three times.
 *
 * <p>Two absences worth naming, both dictated by Redis. There is no query on {@code evaluateAfter}:
 * its derived queries cover equality and nothing else, so "due for evaluation" is a read of one
 * phase and a comparison in memory. And there is no {@code findByStatusIn}: {@link
 * Situation.Status} has two values so that equality is enough.
 */
public interface SituationRepo {

  Situation save(Situation situation);

  Optional<Situation> findById(String id);

  /**
   * The situations under one correlation key in one state — the lookup on the ingest path, asked
   * with {@link Situation.Status#OPEN} to find the situation an arriving observation joins, and
   * with {@link Situation.Status#RESOLVED} to find a recently closed one worth reopening.
   *
   * <p>A list rather than an {@code Optional} because nothing in any backend can enforce that there
   * is at most one: Redis secondary indexes cannot express uniqueness at all, so the invariant is
   * the ingest path's to keep and this signature tells the truth about it.
   */
  List<Situation> findByCorrelationKeyAndStatus(String correlationKey, Situation.Status status);

  List<Situation> findByStatus(Situation.Status status);

  List<Situation> findByPhase(Situation.Phase phase);
}
