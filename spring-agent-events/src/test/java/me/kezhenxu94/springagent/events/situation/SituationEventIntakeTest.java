package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.support.InMemoryRepos;
import me.kezhenxu94.springagent.events.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cheap half: what a surface reports, where it is filed, and when that makes the situation due.
 *
 * <p>The debounce arithmetic is most of what is worth pinning here, because it is the whole reason
 * the feature is affordable — a thousand alerts from one outage have to become one model call — and
 * because every part of it is a rule that only shows up under a specific sequence of arrivals.
 */
class SituationEventIntakeTest {

  private static final Instant START = Instant.parse("2026-08-29T10:00:00Z");
  private static final Duration DEBOUNCE = Duration.ofSeconds(30);
  private static final Duration MAX_DEBOUNCE = Duration.ofMinutes(5);
  private static final Duration COOLDOWN = Duration.ofMinutes(10);

  private final InMemoryRepos repos = new InMemoryRepos();
  private final MutableClock clock = new MutableClock(START);

  private EventsProperties properties;
  private SituationEventIntake intake;

  @BeforeEach
  void setUp() {
    properties = properties(200);
    intake = intake(properties);
  }

  private SituationEventIntake intake(final EventsProperties properties) {
    return new SituationEventIntake(
        properties, trustedActors(properties), repos.situations, repos.events, repos.claims, clock);
  }

  /** The real thing rather than a stub, so these tests exercise the admission path as it ships. */
  private static TrustedActors trustedActors(final EventsProperties properties) {
    final var actors = new TrustedActors(properties);
    actors.compileAll();
    return actors;
  }

  private EventsProperties properties(final int maxEventsPerSituation) {
    return EventsProperties.builder()
        .enabled(true)
        .maxEventsPerSituation(maxEventsPerSituation)
        .debounce(DEBOUNCE)
        .maxDebounce(MAX_DEBOUNCE)
        .cooldown(COOLDOWN)
        .sources(
            Map.of(
                "grafana",
                EventsProperties.Source.builder()
                    .ownerUserId("ou_bot")
                    .route(Route.builder().chatId("oc_alerts").chatType("group").build())
                    .build()))
        .build();
  }

  private static Observation.ObservationBuilder alert(final String deliveryId) {
    return Observation.builder()
        .source("grafana")
        .deliveryId(deliveryId)
        .kind("alert.firing")
        .correlationKey("grafana:abc")
        .title("api latency")
        .summary("p99 over 2s");
  }

  @Test
  @DisplayName("the first observation opens a situation, due one debounce later")
  void shouldOpenASituation() {
    intake.observe(alert("d1").build());

    // Read back rather than returned. observe says nothing now: every intake in the application is
    // given every observation, so "which situation it joined" is one implementation's answer and
    // unanswerable once there is more than one of them.
    final var situation = repos.situations.only();
    assertThat(situation.source()).isEqualTo("grafana");
    assertThat(situation.correlationKey()).isEqualTo("grafana:abc");
    assertThat(situation.title()).isEqualTo("api latency");
    assertThat(situation.status()).isEqualTo(Situation.Status.OPEN);
    assertThat(situation.phase()).isEqualTo(Situation.Phase.AWAITING_EVALUATION);
    assertThat(situation.eventCount()).isEqualTo(1);
    assertThat(situation.firstSeenAt()).isEqualTo(START);
    assertThat(situation.awaitingSince()).isEqualTo(START);
    assertThat(situation.evaluateAfter()).isEqualTo(START.plus(DEBOUNCE));
    assertThat(repos.events.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("a webhook knows nowhere to talk, and the source's route does not fill that in")
  void shouldNotTakeTheRouteFromTheSourceWhenTheObservationHasNone() {
    // The source in this test is configured with oc_alerts, and that is where a *failed* triage is
    // reported — not where a run talks. Filling it in here is what this asserts does not happen:
    // where a run about an alert talks comes from the source's playbook, and a run that was handed
    // a chat by configuration would talk there whatever the playbook said.
    intake.observe(alert("d1").build());

    final var situation = repos.situations.only();
    assertThat(situation.ownerUserId()).isEqualTo("ou_bot");
    assertThat(situation.chatId()).isNull();
    assertThat(situation.chatType()).isNull();
  }

  @Test
  @DisplayName("an observation that knows its own chat keeps it")
  void shouldKeepTheObservationsOwnRoute() {
    // A chat message knows where it came from, and a run about it answers there. This is the only
    // way a situation gets a chat at all.
    intake.observe(
        alert("d1")
            .route(Route.builder().chatId("oc_from_the_message").chatType("group").build())
            .build());

    assertThat(repos.situations.only().chatId()).isEqualTo("oc_from_the_message");
  }

  @Test
  @DisplayName("a thousand alerts about one thing are one situation and one deadline")
  void shouldCollapseABurstIntoOneSituation() {
    for (var i = 0; i < 1000; i++) {
      clock.advance(Duration.ofMillis(10));
      intake.observe(alert("d" + i).build());
    }

    assertThat(repos.situations.all()).hasSize(1);
    final var situation = repos.situations.only();
    assertThat(situation.eventCount()).isEqualTo(1000);
    // Still not due, ten seconds of alerts later: the deadline moved with every one of them, which
    // is what turns the burst into a single evaluation once it stops.
    assertThat(situation.evaluateAfter()).isEqualTo(clock.instant().plus(DEBOUNCE));
    assertThat(situation.evaluateAfter()).isAfter(clock.instant());
  }

  @Test
  @DisplayName("but a source that never stops is looked at anyway, at max-debounce")
  void shouldCapTheDebounce() {
    // The case the cap exists for: something emitting steadily would otherwise push its deadline
    // out for ever, and that is precisely when somebody wants to be told.
    intake.observe(alert("d0").build());

    for (var i = 1; i < 100; i++) {
      clock.advance(Duration.ofSeconds(10));
      intake.observe(alert("d" + i).build());
    }

    final var situation = repos.situations.only();
    // Measured from when the run of unevaluated observations began, not from now — a moving anchor
    // would cap nothing.
    assertThat(situation.awaitingSince()).isEqualTo(START);
    assertThat(situation.evaluateAfter()).isEqualTo(START.plus(MAX_DEBOUNCE));
    assertThat(situation.evaluateAfter()).isBefore(clock.instant());
  }

  @Test
  @DisplayName("after a look, the cooldown is a floor under the next one")
  void shouldHoldTheCooldownAfterAnEvaluation() {
    intake.observe(alert("d1").build());
    final var evaluatedAt = clock.instant();
    repos.situations.save(
        repos.situations.only().toBuilder()
            .phase(Situation.Phase.MONITORING)
            .lastEvaluatedAt(evaluatedAt)
            .awaitingSince(null)
            .generation(1)
            .build());

    clock.advance(Duration.ofSeconds(5));
    intake.observe(alert("d2").build());

    final var situation = repos.situations.only();
    // The debounce alone would have made it due 30s from now; the cooldown pushes it to ten minutes
    // after the last look, which is what stops a busy situation being re-read every debounce.
    assertThat(situation.evaluateAfter()).isEqualTo(evaluatedAt.plus(COOLDOWN));
    assertThat(situation.phase()).isEqualTo(Situation.Phase.AWAITING_EVALUATION);
    // And the cap starts again from this batch rather than from the one already considered.
    assertThat(situation.awaitingSince()).isEqualTo(clock.instant());
  }

  @Test
  @DisplayName("something arriving mid-evaluation does not start a second one")
  void shouldNotDisturbAnEvaluationInFlight() {
    // Moving the phase back to AWAITING_EVALUATION here would let the sweeper pick the situation up
    // while a run was still going, and two runs for one situation is two chime-ins in a chat.
    intake.observe(alert("d1").build());
    repos.situations.save(
        repos.situations.only().toBuilder().phase(Situation.Phase.INVESTIGATING).build());

    clock.advance(Duration.ofSeconds(1));
    intake.observe(alert("d2").build());

    final var situation = repos.situations.only();
    assertThat(situation.phase()).isEqualTo(Situation.Phase.INVESTIGATING);
    // The observation is still recorded and still moves the deadline; only the phase is left alone,
    // for the lifecycle listener to put back when the run ends.
    assertThat(situation.eventCount()).isEqualTo(2);
    assertThat(situation.lastEventAt()).isEqualTo(clock.instant());
  }

  @Test
  @DisplayName("a redelivery is dropped, and its situation is untouched")
  void shouldDropADuplicateDelivery() {
    intake.observe(alert("d1").build());

    clock.advance(Duration.ofSeconds(1));
    intake.observe(alert("d1").build());

    // Dropped, and the proof is that nothing moved: not the count, not the deadline, not the rows.
    final var situation = repos.situations.only();
    assertThat(situation.eventCount()).isEqualTo(1);
    assertThat(situation.evaluateAfter()).isEqualTo(START.plus(DEBOUNCE));
    assertThat(repos.events.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("two systems using the same delivery id do not silence each other")
  void shouldNamespaceDeliveryIdsBySource() {
    final var properties =
        EventsProperties.builder()
            .enabled(true)
            .maxEventsPerSituation(200)
            .debounce(DEBOUNCE)
            .maxDebounce(MAX_DEBOUNCE)
            .cooldown(COOLDOWN)
            .sources(
                Map.of(
                    "grafana",
                    EventsProperties.Source.builder().ownerUserId("ou_bot").build(),
                    "github",
                    EventsProperties.Source.builder().ownerUserId("ou_bot").build()))
            .build();
    final var intake = intake(properties);

    intake.observe(alert("1").build());
    intake.observe(
        Observation.builder()
            .source("github")
            .deliveryId("1")
            .correlationKey("github:x#1")
            .kind("issues.opened")
            .build());

    assertThat(repos.situations.all()).hasSize(2);
  }

  @Test
  @DisplayName("different correlation keys are different situations")
  void shouldSeparateDifferentCorrelationKeys() {
    intake.observe(alert("d1").correlationKey("grafana:abc").build());
    intake.observe(alert("d2").correlationKey("grafana:def").build());

    assertThat(repos.situations.all()).hasSize(2);
  }

  @Test
  @DisplayName("a resolved situation is not rejoined; the next observation opens a new one")
  void shouldOpenANewSituationAfterResolution() {
    intake.observe(alert("d1").build());
    repos.situations.save(
        repos.situations.only().toBuilder().status(Situation.Status.RESOLVED).build());

    clock.advance(Duration.ofHours(9));
    intake.observe(alert("d2").build());

    // Two rows, one closed and one open: a recurrence after the situation was closed is a new
    // episode, which is what makes a reopen path unnecessary. Anything recurring while it is still
    // open joins it instead, because resolve-after-quiet is far longer than any debounce.
    assertThat(repos.situations.all()).hasSize(2);
    assertThat(repos.situations.findByStatus(Situation.Status.OPEN)).hasSize(1);
  }

  @Test
  @DisplayName("an unconfigured source is dropped without a claim, so configuring it later works")
  void shouldDropAnUnconfiguredSourceWithoutClaiming() {
    intake.observe(
        Observation.builder().source("gitlab").deliveryId("d1").correlationKey("gitlab:x").build());

    assertThat(repos.situations.all()).isEmpty();
    // The claim matters: taking one here would mean that turning the source on later silently
    // ignored every delivery seen while it was off, and claims never expire.
    assertThat(repos.claims.size()).isZero();
  }

  @Test
  @DisplayName("an untrusted actor is dropped without a claim, so widening the list later works")
  void shouldDropAnUntrustedActorWithoutClaiming() {
    final var strict = trusted("octocat");
    final var intake = intake(strict);

    intake.observe(alert("d1").actor("mallory").build());

    assertThat(repos.situations.all()).isEmpty();
    // The same reasoning as the unconfigured source above, and it bites harder here. A claim never
    // expires, so a refusal taken before the deployment fixed its list would outlive the mistake:
    // the operator widens the list, the event arrives again, and it is passed over for good as
    // something already seen.
    assertThat(repos.claims.size()).isZero();

    intake(trusted("octocat", "mallory")).observe(alert("d1").actor("mallory").build());

    assertThat(repos.situations.all()).hasSize(1);
  }

  @Test
  @DisplayName("a trusted actor is recorded as any other observation is")
  void shouldRecordATrustedActor() {
    intake(trusted("octocat")).observe(alert("d1").actor("octocat").build());

    assertThat(repos.situations.all()).hasSize(1);
    assertThat(repos.events.size()).isEqualTo(1);
  }

  /** The alert source, but willing to hear only from {@code actors}. */
  private EventsProperties trusted(final String... actors) {
    return EventsProperties.builder()
        .enabled(true)
        .maxEventsPerSituation(200)
        .debounce(DEBOUNCE)
        .maxDebounce(MAX_DEBOUNCE)
        .cooldown(COOLDOWN)
        .sources(
            Map.of(
                "grafana",
                EventsProperties.Source.builder()
                    .ownerUserId("ou_bot")
                    .trustedActors(List.of(actors))
                    .build()))
        .build();
  }

  @Test
  @DisplayName("past the cap an observation is counted but not stored")
  void shouldStopStoringPastTheCap() {
    final var intake = intake(properties(3));

    for (var i = 0; i < 10; i++) {
      clock.advance(Duration.ofMillis(10));
      intake.observe(alert("d" + i).build());
    }

    assertThat(repos.events.size()).isEqualTo(3);
    // The count is still true, which is the number anybody reasons about at that scale.
    assertThat(repos.situations.only().eventCount()).isEqualTo(10);
  }

  @Test
  @DisplayName("a failure gives the claim back, so a redelivery is not lost for good")
  void shouldReleaseTheClaimOnFailure() {
    final var exploding =
        new SituationEventIntake(
            properties,
            trustedActors(properties),
            repos.situations,
            new me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo() {
              @Override
              public me.kezhenxu94.springagent.core.dao.models.ObservedEvent save(
                  final me.kezhenxu94.springagent.core.dao.models.ObservedEvent event) {
                throw new IllegalStateException("no room");
              }

              @Override
              public java.util.List<me.kezhenxu94.springagent.core.dao.models.ObservedEvent>
                  findBySituationId(final String situationId) {
                return java.util.List.of();
              }
            },
            repos.claims,
            clock);

    assertThatThrownBy(() -> exploding.observe(alert("d1").build()))
        .isInstanceOf(IllegalStateException.class);

    // Holding the claim would leave the observation unrecorded and every redelivery of it ignored,
    // which is worse than the duplicate the claim exists to prevent.
    // Namespaced to this intake, so that an application's own intake claiming the same delivery
    // cannot silence this one.
    assertThat(repos.claims.isClaimed("situations:observed:grafana:d1")).isFalse();
    intake.observe(alert("d1").build());
    assertThat(repos.situations.all()).hasSize(1);
  }

  @Test
  @DisplayName("a source that sent no title still gets a situation worth finding in a log")
  void shouldFallBackToTheCorrelationKeyForATitle() {
    intake.observe(alert("d1").title(null).build());

    assertThat(repos.situations.only().title()).isEqualTo("grafana:abc");
  }
}
