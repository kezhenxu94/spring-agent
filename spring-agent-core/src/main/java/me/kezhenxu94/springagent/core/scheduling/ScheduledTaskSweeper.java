package me.kezhenxu94.springagent.core.scheduling;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Decides when a scheduled task fires, by asking the database rather than by holding a timer.
 *
 * <p>The shape is {@code SituationSweeper}'s, deliberately, and for the same payoff: everything is
 * driven from stored state, so a restart in the middle of a schedule is a non-event and two
 * replicas can share the work. What a timer could not do is exactly what was wrong before — an
 * occurrence that fell while the process was down was skipped in silence, because a re-armed {@code
 * CronTrigger} computes the next occurrence in the future and nothing remembers the one that was
 * missed. Here a missed occurrence is not a special case at all: a {@code nextFireAt} in the past
 * is simply due.
 *
 * <p>The one thing two replicas must not both do is fire the same occurrence, and {@link
 * ScheduledTaskRepo#claimNextFireAt} is how that is settled — winning the occurrence and recording
 * that it has been taken are one conditional write, so the loser finds the task already moved on.
 *
 * @see ScheduledTaskService for what a firing actually does
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskSweeper {

  private final SpringAgent springAgent;
  private final ScheduledTaskRepo tasks;
  private final ScheduledTaskService service;
  private final SpringAgentProperties properties;
  private final ThreadPoolTaskScheduler taskScheduler;
  private final Clock clock;

  private volatile ScheduledFuture<?> sweep;

  @PostConstruct
  void start() {
    final var interval = properties.scheduling().sweepInterval();
    sweep = taskScheduler.scheduleWithFixedDelay(this::sweepQuietly, interval);
    log.info("Watching for scheduled tasks to fire every {}", interval);
  }

  @PreDestroy
  void stop() {
    if (sweep != null) {
      sweep.cancel(false);
    }
  }

  /**
   * Catches everything, and has to.
   *
   * <p>A task scheduled with a fixed delay is not run again after it throws — the executor drops it
   * silently. So an exception escaping one sweep would not fail loudly, it would stop every
   * scheduled task in the deployment for the lifetime of the process while each of them sat there
   * looking due.
   */
  void sweepQuietly() {
    try {
      sweep();
    } catch (Throwable t) {
      log.error("A sweep failed; the next one will try again", t);
    }
  }

  void sweep() {
    if (!springAgent.accepting()) {
      log.debug("Shutting down, skipping the sweep");
      return;
    }
    final var now = clock.instant();

    // Three passes over one read. Retiring first because it is what keeps the active set — and so
    // every sweep — small, and because a task retired here must not then be backfilled or fired.
    final var live = new ArrayList<ScheduledTask>();
    for (final var task : tasks.findByStatus(ScheduledTask.Status.ACTIVE)) {
      guarded(
          task,
          () -> {
            if (!retire(task, now)) {
              live.add(task);
            }
          });
    }

    live.stream()
        .filter(task -> task.nextFireAt() == null)
        .forEach(task -> guarded(task, () -> backfill(task, now)));

    live.stream()
        .filter(task -> due(task, now))
        // Longest overdue first, so a backlog drains in the order things became due rather than in
        // whatever order the index happened to return them.
        .sorted(Comparator.comparing(ScheduledTask::nextFireAt))
        .forEach(task -> guarded(task, () -> claimAndFire(task, now)));
  }

  /**
   * One task's turn, walled off from the rest of the sweep.
   *
   * <p>{@code SituationSweeper} does not do this and does not need to; here a sweep touches every
   * active task in the deployment, and one unparseable cron expression must not stop the other
   * forty-nine from firing this minute and every minute after.
   */
  private void guarded(final ScheduledTask task, final Runnable work) {
    try {
      work.run();
    } catch (RuntimeException e) {
      log.error("Could not deal with scheduled task {} this sweep", task.id(), e);
    }
  }

  /**
   * Takes a task that has nothing left to do out of the active set, and says whether it did.
   *
   * <p>This is what {@code ScheduledTaskService#init} used to do once, at startup. Doing it every
   * sweep is a straight improvement: before, a task that expired while the process was up stayed
   * active until its next firing noticed, so a yearly task that expired in January was carried all
   * year.
   */
  private boolean retire(final ScheduledTask task, final Instant now) {
    if (task.expiresAt() != null && task.expiresAt().isBefore(now)) {
      log.info("Scheduled task {} has expired, cancelling", task.id());
      tasks.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
      return true;
    }
    final var runsSoFar = task.runCount() == null ? 0 : task.runCount();
    if (task.maxRuns() != null && runsSoFar >= task.maxRuns()) {
      log.info(
          "Scheduled task {} has had all {} of its runs, completing", task.id(), task.maxRuns());
      tasks.updateStatus(task.id(), ScheduledTask.Status.COMPLETED);
      return true;
    }
    // A one-off with no next time and a firing behind it has nothing left. Normally the run's own
    // listener has already written a terminal status; this is what catches the task whose run never
    // came back at all, which would otherwise be examined on every sweep for ever.
    if (task.nextFireAt() == null && task.cronExpression() == null && runsSoFar > 0) {
      log.info("Scheduled task {} has had its one firing, completing", task.id());
      tasks.updateStatus(task.id(), ScheduledTask.Status.COMPLETED);
      return true;
    }
    return false;
  }

  /**
   * Gives a task with no next time recorded its first.
   *
   * <p>What needs it is a task written before {@code nextFireAt} existed: the schema is {@code
   * ddl-auto} with no migrations, so the column arrives null on every existing row and is simply
   * absent on MongoDB and Redis. Backfilled here rather than by something at startup, which would
   * have nothing to say about an older replica still writing such rows through a rolling upgrade.
   *
   * <p>Conditional, so two replicas meeting the same legacy row do not both set it; the loser
   * simply finds it set on its next sweep. Not fired in the same pass either — the task becomes due
   * like any other, which for a one-off whose time has passed means the next sweep, seconds away.
   */
  private void backfill(final ScheduledTask task, final Instant now) {
    final var next = ScheduledTaskService.nextFireAtFor(task, now);
    if (next == null) {
      // A cron expression with no further occurrence. Nothing to record and nothing to fire; the
      // task is left alone rather than retired, since only a one-off is finished by having no next.
      log.warn(
          "Scheduled task {} has no next occurrence for cron '{}'",
          task.id(),
          task.cronExpression());
      return;
    }
    if (tasks.initNextFireAt(task.id(), next)) {
      log.info("Scheduled task {} had no next time recorded; it now fires at {}", task.id(), next);
    }
  }

  private boolean due(final ScheduledTask task, final Instant now) {
    return task.nextFireAt() != null && !task.nextFireAt().isAfter(now);
  }

  /**
   * Wins the occurrence, then fires it.
   *
   * <p>In that order, and it matters which. The conditional write both settles who fires and
   * records that this occurrence is spent, so a replica that dies immediately afterwards loses one
   * firing and the task recovers by itself at the next — where a mark taken and never cleared would
   * leave the task looking due for ever and refusing every later attempt. It is the same bargain
   * {@code ScheduledTaskService#fire} already makes by counting the run before starting it.
   */
  private void claimAndFire(final ScheduledTask task, final Instant now) {
    if (service.isFiring(task.id())) {
      // Its previous firing is still going. Left due, so the next sweep looks again rather than the
      // occurrence being spent on a run that would have collided with the one in flight.
      log.info(
          "Scheduled task {} is still running its last firing, leaving it for the next sweep",
          task.id());
      return;
    }
    final var next = ScheduledTaskService.nextFireAtAfterFiring(task, now);
    if (!tasks.claimNextFireAt(task.id(), task.nextFireAt(), next)) {
      log.debug("Another replica is firing scheduled task {}", task.id());
      return;
    }
    if (task.nextFireAt().isBefore(now.minus(properties.scheduling().sweepInterval()))) {
      // Said out loud, because this is the case the sweeper exists for and the one an operator will
      // want to see after an outage. Only one firing happens however many occurrences were missed —
      // see ScheduledTaskService#nextFireAtFor for why.
      log.info(
          "Scheduled task {} was due at {} and is only firing now; it next fires at {}",
          task.id(),
          task.nextFireAt(),
          next);
    }
    service.fire(task);
  }
}
