package me.kezhenxu94.springagent.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * What decides that a task fires, and what stops it firing twice.
 *
 * <p>Nothing here waits on a real timer: {@link ScheduledTaskSweeper#sweep()} is called directly
 * and the clock is moved by hand, which is the only way the case this feature exists for — an
 * occurrence missed over three days of downtime — is testable at all.
 */
class ScheduledTaskSweeperTest {

  private static final String EVERY_FIVE_MINUTES = "0 */5 * * * *";
  private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

  private MutableClock clock;
  private InMemoryScheduledTaskRepo tasks;
  private SpringAgent springAgent;
  private ScheduledTaskService service;
  private ThreadPoolTaskScheduler taskScheduler;
  private ScheduledTaskSweeper sweeper;

  @BeforeEach
  void setUp() {
    clock = MutableClock.at("2026-08-31T10:00:00Z");
    tasks = new InMemoryScheduledTaskRepo();
    springAgent = mock(SpringAgent.class);
    when(springAgent.accepting()).thenReturn(true);
    service = mock(ScheduledTaskService.class);
    taskScheduler = mock(ThreadPoolTaskScheduler.class);
    sweeper = newSweeper();
  }

  private ScheduledTaskSweeper newSweeper() {
    return new ScheduledTaskSweeper(
        springAgent, tasks, service, properties(), taskScheduler, clock);
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null, null, null, null, new SpringAgentProperties.Scheduling(SWEEP_INTERVAL, null));
  }

  private ScheduledTask.ScheduledTaskBuilder task(final String id) {
    return ScheduledTask.builder()
        .id(id)
        .userId("u1")
        .taskText("do the thing")
        .status(ScheduledTask.Status.ACTIVE);
  }

  private ScheduledTask stored(final String id) {
    return tasks.findById(id).orElseThrow();
  }

  @Test
  @DisplayName("a task whose next firing has passed is fired")
  void shouldFireADueTask() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());

    sweeper.sweep();

    verify(service).fire(any());
  }

  @Test
  @DisplayName("a task whose next firing is still ahead is left alone")
  void shouldNotFireBeforeTheTime() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().plusSeconds(60))
            .build());

    sweeper.sweep();

    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("firing a task moves it on to its next occurrence")
  void shouldAdvanceTheOccurrence() {
    final var wasDueAt = clock.instant().minusSeconds(10);
    tasks.save(task("t1").cronExpression(EVERY_FIVE_MINUTES).nextFireAt(wasDueAt).build());

    sweeper.sweep();

    assertThat(stored("t1").nextFireAt()).isAfter(clock.instant()).isNotEqualTo(wasDueAt);
  }

  @Test
  @DisplayName("a backlog drains longest-overdue first")
  void shouldDrainInOrder() {
    tasks.save(
        task("recent")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());
    tasks.save(
        task("ancient")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(600))
            .build());

    final var fired = new ArrayList<String>();
    doAnswerRecording(fired);

    sweeper.sweep();

    assertThat(fired).containsExactly("ancient", "recent");
  }

  @Test
  @DisplayName("only one of two replicas fires an occurrence")
  void shouldLetOnlyOneReplicaFire() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());

    // Two sweepers over one store, each with the copy of the task it read for itself — which is
    // exactly the position two replicas are in.
    final var other = newSweeper();
    sweeper.sweep();
    other.sweep();

    verify(service).fire(any());
  }

  @Test
  @DisplayName("three days of downtime costs one firing, not eight hundred")
  void shouldCollapseAMissedBacklog() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().plusSeconds(60))
            .build());

    clock.advance(Duration.ofDays(3));
    sweeper.sweep();

    verify(service).fire(any());
    // And it is due again five minutes from now rather than from where it left off three days ago.
    assertThat(stored("t1").nextFireAt())
        .isAfter(clock.instant())
        .isBeforeOrEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
  }

  @Test
  @DisplayName("a one-off whose time has passed fires once and never again")
  void shouldFireALateOneOffExactlyOnce() {
    tasks.save(
        task("t1")
            .scheduledAt(clock.instant().minusSeconds(600))
            .nextFireAt(clock.instant().minusSeconds(600))
            .build());

    sweeper.sweep();
    verify(service).fire(any());
    assertThat(stored("t1").nextFireAt()).isNull();

    // The firing's own listener writes the terminal status; here the run never came back, so the
    // sweeper is what has to notice there is nothing left to do.
    tasks.incrementRunCount("t1");
    sweeper.sweep();

    verify(service).fire(any());
    assertThat(stored("t1").status()).isEqualTo(ScheduledTask.Status.COMPLETED);
  }

  @Test
  @DisplayName("a task written before nextFireAt existed is given one, and not fired the same pass")
  void shouldBackfillALegacyCronTask() {
    tasks.save(task("t1").cronExpression(EVERY_FIVE_MINUTES).build());

    sweeper.sweep();

    assertThat(stored("t1").nextFireAt()).isNotNull().isAfter(clock.instant());
    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("a legacy one-off that never ran keeps its time, however far past")
  void shouldBackfillALegacyOneOff() {
    final var wasDueAt = clock.instant().minus(Duration.ofDays(2));
    tasks.save(task("t1").scheduledAt(wasDueAt).build());

    sweeper.sweep();

    assertThat(stored("t1").nextFireAt()).isEqualTo(wasDueAt);
    verify(service, never()).fire(any());

    // Due on the very next sweep, which is what "a one-off missed while we were down still happens"
    // amounts to.
    sweeper.sweep();
    verify(service).fire(any());
  }

  @Test
  @DisplayName("a legacy one-off that already ran is retired rather than backfilled")
  void shouldRetireALegacyOneOffThatHasRun() {
    tasks.save(task("t1").scheduledAt(clock.instant().minusSeconds(60)).runCount(1).build());

    sweeper.sweep();

    assertThat(stored("t1").status()).isEqualTo(ScheduledTask.Status.COMPLETED);
    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("an expired task is cancelled instead of fired")
  void shouldCancelAnExpiredTask() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .expiresAt(clock.instant().minusSeconds(1))
            .build());

    sweeper.sweep();

    assertThat(stored("t1").status()).isEqualTo(ScheduledTask.Status.CANCELLED);
    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("a task that has had all its runs is completed instead of fired")
  void shouldCompleteAnExhaustedTask() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .maxRuns(3)
            .runCount(3)
            .build());

    sweeper.sweep();

    assertThat(stored("t1").status()).isEqualTo(ScheduledTask.Status.COMPLETED);
    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("a task still running its last firing is left due rather than started again")
  void shouldNotOverlapAFiring() {
    final var wasDueAt = clock.instant().minusSeconds(10);
    tasks.save(task("t1").cronExpression(EVERY_FIVE_MINUTES).nextFireAt(wasDueAt).build());
    when(service.isFiring("t1")).thenReturn(true);

    sweeper.sweep();

    verify(service, never()).fire(any());
    // Left due, not spent: the occurrence is still there for the next sweep to reconsider.
    assertThat(stored("t1").nextFireAt()).isEqualTo(wasDueAt);
  }

  @Test
  @DisplayName("nothing is fired while the agent is shutting down")
  void shouldDoNothingWhileShuttingDown() {
    when(springAgent.accepting()).thenReturn(false);
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());

    sweeper.sweep();

    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("one broken task does not stop the rest of the sweep")
  void shouldSurviveOneBadTask() {
    tasks.save(
        task("broken")
            .cronExpression("not a cron")
            .nextFireAt(clock.instant().minusSeconds(20))
            .build());
    tasks.save(
        task("fine")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());

    final var fired = new ArrayList<String>();
    doAnswerRecording(fired);

    sweeper.sweep();

    assertThat(fired).containsExactly("fine");
  }

  @Test
  @DisplayName("a sweep that throws does not stop every later sweep")
  void shouldSwallowAFailingSweep() {
    tasks.save(
        task("t1")
            .cronExpression(EVERY_FIVE_MINUTES)
            .nextFireAt(clock.instant().minusSeconds(10))
            .build());
    // A fixed-delay task is dropped by the executor once it throws, so this catching is the whole
    // difference between one bad sweep and the feature being gone for the life of the process.
    when(springAgent.accepting()).thenThrow(new IllegalStateException("boom"));

    sweeper.sweepQuietly();

    verify(service, never()).fire(any());
  }

  @Test
  @DisplayName("the sweep is scheduled at the configured interval, and cancelled on shutdown")
  void shouldScheduleItselfOnStart() {
    final var future = mock(java.util.concurrent.ScheduledFuture.class);
    when(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
        .thenReturn(future);

    sweeper.start();
    verify(taskScheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class), org.mockito.ArgumentMatchers.eq(SWEEP_INTERVAL));

    sweeper.stop();
    verify(future).cancel(false);
  }

  /** Records the ids fired, in order, so the tests can say which and in what sequence. */
  private void doAnswerRecording(final List<String> fired) {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              fired.add(invocation.getArgument(0, ScheduledTask.class).id());
              return null;
            })
        .when(service)
        .fire(any());
  }
}
