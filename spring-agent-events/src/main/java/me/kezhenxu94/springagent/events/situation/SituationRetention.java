package me.kezhenxu94.springagent.events.situation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Forgets closed situations, and the observations behind them, once they are older than {@code
 * app.events.retention}.
 *
 * <p>Nothing else here ever deletes anything. {@code SituationSweeper.resolveQuiet} closes a
 * situation, which takes it out of every query on the ingest and evaluation paths but leaves the
 * row — so without this the two tables are append-only, and a deployment watching a busy alert
 * source accumulates them for the lifetime of the database. That is a storage bill nobody decided
 * to pay, and on Redis it is also a working set: every row is a hash plus its index entries, held
 * in memory whether or not anything will ever read it again.
 *
 * <p>Deleting is expressed as "read the closed ones, decide in memory, delete by id" for the reason
 * every other query in this module is: no backend here serves a range predicate over a timestamp,
 * Redis least of all. {@code findByStatus(RESOLVED)} is a single indexed read, and it is the only
 * pass that touches the closed set at all — which is why this runs on a timer of its own at {@link
 * EventsProperties#RETENTION_SWEEP_INTERVAL} rather than inside the five-second sweep, where it
 * would hydrate the whole backlog twelve times a minute to find nothing.
 *
 * <p>Only {@link Situation.Status#RESOLVED} situations are considered, and age is measured from
 * when they were closed. An open situation is never deleted however old it is: something is still
 * arriving under its correlation key, and taking it away would split one ongoing concern into two.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SituationRetention {

  private final SituationRepo situations;
  private final ObservedEventRepo events;
  private final EventsProperties properties;
  private final ThreadPoolTaskScheduler taskScheduler;
  private final Clock clock;

  private volatile ScheduledFuture<?> sweep;

  @PostConstruct
  void start() {
    if (properties.retention().isZero()) {
      log.info("app.events.retention is 0, so closed situations are kept for ever");
      return;
    }
    sweep =
        taskScheduler.scheduleWithFixedDelay(
            this::purgeQuietly, EventsProperties.RETENTION_SWEEP_INTERVAL);
    log.info("Deleting closed situations older than {}", properties.retention());
  }

  @PreDestroy
  void stop() {
    if (sweep != null) {
      sweep.cancel(false);
    }
  }

  /**
   * Catches everything, for the reason {@code SituationSweeper.sweepQuietly} does: a task scheduled
   * with a fixed delay is dropped silently after it throws, so an exception escaping one pass would
   * turn retention off for the lifetime of the process without failing anywhere visible.
   */
  void purgeQuietly() {
    try {
      final var deleted = purge();
      if (deleted > 0) {
        log.info(
            "Deleted {} situation(s) closed longer than {} ago", deleted, properties.retention());
      }
    } catch (Throwable t) {
      log.error("A retention pass failed; the next one will try again", t);
    }
  }

  /**
   * Deletes what is past its retention, and says how many.
   *
   * <p>The observations go first. A crash between the two deletes then leaves a situation with no
   * evidence, which the next pass will delete; the other order would leave observations no query
   * can reach, since nothing enumerates them except by the situation that has just gone.
   */
  int purge() {
    final var deadline = clock.instant().minus(properties.retention());
    var deleted = 0;
    for (final var situation : situations.findByStatus(Situation.Status.RESOLVED)) {
      final var closedAt = closedAt(situation);
      if (closedAt == null) {
        // Nothing on the row says when it happened, so nothing here can say it is old enough. Rows
        // written before resolvedAt existed are the case this covers, and keeping one is the
        // harmless answer: it will never be read, and it will never be deleted either.
        log.debug("Situation {} has no timestamp to age it by; keeping it", situation.id());
        continue;
      }
      if (!closedAt.isBefore(deadline)) {
        continue;
      }
      events.deleteBySituationId(situation.id());
      situations.deleteById(situation.id());
      deleted++;
    }
    return deleted;
  }

  /**
   * When this situation stopped being a live concern, as well as the row can say.
   *
   * <p>{@code resolvedAt} is written by everything that closes one, so the fallbacks are for rows
   * that predate the field rather than for a path that forgets it.
   */
  private static Instant closedAt(final Situation situation) {
    if (situation.resolvedAt() != null) {
      return situation.resolvedAt();
    }
    return situation.lastEventAt() != null ? situation.lastEventAt() : situation.firstSeenAt();
  }
}
