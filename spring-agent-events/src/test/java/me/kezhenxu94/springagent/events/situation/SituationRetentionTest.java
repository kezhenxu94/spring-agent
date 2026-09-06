package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.support.InMemoryRepos;
import me.kezhenxu94.springagent.events.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * What is forgotten, and what is not.
 *
 * <p>The interesting cases are all about what must survive a pass, rather than about what goes: an
 * open situation is somebody's ongoing concern whatever its age, and a row with nothing to date it
 * by cannot be shown to be past its retention. Deleting one of those is not recoverable, so each
 * has a test of its own.
 */
class SituationRetentionTest {

  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
  private static final Duration RETENTION = Duration.ofDays(30);

  private final InMemoryRepos repos = new InMemoryRepos();
  private final MutableClock clock = new MutableClock(NOW);
  private final ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);

  private SituationRetention retention(final Duration keepFor) {
    return new SituationRetention(
        repos.situations,
        repos.events,
        EventsProperties.builder().enabled(true).retention(keepFor).build(),
        scheduler,
        clock);
  }

  private Situation closed(final String id, final Instant resolvedAt) {
    final var situation =
        repos.situations.save(
            Situation.builder()
                .id(id)
                .source("grafana")
                .correlationKey("grafana:" + id)
                .status(Situation.Status.RESOLVED)
                .phase(Situation.Phase.MONITORING)
                .firstSeenAt(resolvedAt == null ? null : resolvedAt.minus(Duration.ofHours(6)))
                .resolvedAt(resolvedAt)
                .build());
    repos.events.save(
        ObservedEvent.builder().id(id + "-delivery").situationId(id).summary("something").build());
    return situation;
  }

  @Test
  @DisplayName("a situation closed longer ago than the retention goes, with its observations")
  void oldClosedSituationsAreDeleted() {
    closed("old", NOW.minus(Duration.ofDays(31)));

    assertThat(retention(RETENTION).purge()).isEqualTo(1);

    assertThat(repos.situations.all()).isEmpty();
    // The evidence goes with it. Nothing enumerates observations except by their situation, so one
    // left behind here would be a row no query could ever reach again.
    assertThat(repos.events.size()).isZero();
  }

  @Test
  @DisplayName("a situation closed within the retention stays, with its observations")
  void recentlyClosedSituationsAreKept() {
    closed("recent", NOW.minus(Duration.ofDays(29)));

    assertThat(retention(RETENTION).purge()).isZero();

    assertThat(repos.situations.all()).extracting(Situation::id).containsExactly("recent");
    assertThat(repos.events.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("an open situation is never deleted, however old it is")
  void openSituationsSurviveAnyAge() {
    repos.situations.save(
        Situation.builder()
            .id("still-going")
            .source("grafana")
            .correlationKey("grafana:still-going")
            .status(Situation.Status.OPEN)
            .phase(Situation.Phase.AWAITING_EVALUATION)
            .firstSeenAt(NOW.minus(Duration.ofDays(400)))
            .lastEventAt(NOW.minus(Duration.ofDays(400)))
            .build());

    assertThat(retention(RETENTION).purge()).isZero();
    assertThat(repos.situations.all()).extracting(Situation::id).containsExactly("still-going");
  }

  @Test
  @DisplayName("a closed situation with no timestamp to age it by is kept rather than guessed at")
  void undatedSituationsAreKept() {
    closed("undated", null);

    assertThat(retention(RETENTION).purge()).isZero();
    assertThat(repos.situations.all()).extracting(Situation::id).containsExactly("undated");
  }

  @Test
  @DisplayName("a closed situation is aged by lastEventAt where it was written before resolvedAt")
  void undatedByResolutionFallsBackToTheLastObservation() {
    repos.situations.save(
        Situation.builder()
            .id("legacy")
            .source("grafana")
            .correlationKey("grafana:legacy")
            .status(Situation.Status.RESOLVED)
            .phase(Situation.Phase.MONITORING)
            .firstSeenAt(NOW.minus(Duration.ofDays(90)))
            .lastEventAt(NOW.minus(Duration.ofDays(89)))
            .build());

    assertThat(retention(RETENTION).purge()).isEqualTo(1);
    assertThat(repos.situations.all()).isEmpty();
  }

  @Test
  @DisplayName("a retention of zero schedules nothing at all")
  void zeroRetentionKeepsEverything() {
    closed("ancient", NOW.minus(Duration.ofDays(4000)));

    final var keepForEver = retention(Duration.ZERO);
    keepForEver.start();

    // Not merely "deletes nothing this pass": the timer is never started, so nothing ever reads the
    // closed set at all. A deployment whose retention is somebody else's policy pays nothing for
    // saying so.
    verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    assertThat(repos.situations.all()).extracting(Situation::id).containsExactly("ancient");
  }

  @Test
  @DisplayName("a failing pass is caught, so the timer keeps the next one")
  void aFailingPassDoesNotKillTheTimer() {
    // A task scheduled with a fixed delay is dropped silently once it throws, which would turn
    // retention off for the lifetime of the process without failing anywhere anybody looks.
    final var exploding =
        new SituationRetention(
            repos.situations,
            repos.events,
            EventsProperties.builder().enabled(true).retention(RETENTION).build(),
            scheduler,
            new Clock() {
              @Override
              public Instant instant() {
                throw new IllegalStateException("no clock today");
              }

              @Override
              public ZoneId getZone() {
                return ZoneId.of("UTC");
              }

              @Override
              public Clock withZone(final ZoneId zone) {
                return this;
              }
            });

    exploding.purgeQuietly();
  }
}
