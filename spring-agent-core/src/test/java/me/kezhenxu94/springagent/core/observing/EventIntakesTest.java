package me.kezhenxu94.springagent.core.observing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That an application may consume the same observations more than once, for unrelated reasons.
 *
 * <p>The case this exists for: a deployment wants {@code spring-agent-events} to correlate GitHub
 * deliveries into situations and reason about the ones that matter, and also wants a line in a chat
 * whenever anything happens at all. Those are two consumers of one fact, neither of which should
 * know the other exists, and one of which an application writes itself.
 */
class EventIntakesTest {

  private static Observation anObservation() {
    return Observation.builder()
        .source("github")
        .deliveryId("d1")
        .correlationKey("github:acme/widgets#42")
        .summary("somebody opened an issue")
        .build();
  }

  /** Stands in for an intake an application added of its own. */
  private static final class Recording implements EventIntake {
    private final List<Observation> seen = new ArrayList<>();

    @Override
    public void observe(final Observation observation) {
      seen.add(observation);
    }
  }

  @Test
  @DisplayName("every intake sees every observation")
  void shouldFanOutToAllOfThem() {
    final var situations = new Recording();
    final var notifications = new Recording();

    new EventIntakes(List.of(situations, notifications)).observe(anObservation());

    assertThat(situations.seen).extracting(Observation::deliveryId).containsExactly("d1");
    assertThat(notifications.seen).extracting(Observation::deliveryId).containsExactly("d1");
  }

  @Test
  @DisplayName("one that fails does not take the others with it")
  void shouldIsolateAFailingIntake() {
    // The reason this class exists rather than a plain list at each call site. A consumer whose
    // database is away must not stop another from posting its message — nor stop the transport from
    // acknowledging the event, since a webhook that answers 500 gets redelivered.
    final var afterTheFailure = new Recording();
    final EventIntake failing =
        observation -> {
          throw new IllegalStateException("the database is away");
        };

    final var intakes = new EventIntakes(List.of(failing, afterTheFailure));

    assertThatCode(() -> intakes.observe(anObservation())).doesNotThrowAnyException();
    assertThat(afterTheFailure.seen).hasSize(1);
  }

  @Test
  @DisplayName("an application with no intake at all observes nothing, and says so cheaply")
  void shouldDoNothingWithNoIntakes() {
    // A supported configuration, not a degraded one: it is what a deployment without
    // spring-agent-events looks like. isEmpty is what lets a transport skip reading a message out
    // of
    // an event that nothing would do anything with.
    final var intakes = new EventIntakes(List.of());

    assertThat(intakes.isEmpty()).isTrue();
    assertThatCode(() -> intakes.observe(anObservation())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("and one intake is not empty, whatever it does with what it is given")
  void shouldNotBeEmptyWithOneIntake() {
    assertThat(new EventIntakes(List.of(observation -> {})).isEmpty()).isFalse();
  }
}
