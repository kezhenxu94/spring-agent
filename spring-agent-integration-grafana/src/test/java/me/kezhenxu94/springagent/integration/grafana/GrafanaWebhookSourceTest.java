package me.kezhenxu94.springagent.integration.grafana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import org.junit.jupiter.api.Test;

class GrafanaWebhookSourceTest {

  private static final String SECRET = "grafana-contact-point-secret";

  private static final String TWO_ALERTS =
      """
      {
        "status": "firing",
        "alerts": [
          {
            "status": "firing",
            "labels": {"alertname": "DiskFull", "instance": "db-1", "severity": "critical"},
            "annotations": {"summary": "Disk is 97% full"},
            "fingerprint": "aaaa1111",
            "startsAt": "2026-08-29T10:00:00Z"
          },
          {
            "status": "firing",
            "labels": {"alertname": "QueueBacklog", "instance": "worker-2"},
            "annotations": {"description": "40k messages waiting"},
            "fingerprint": "bbbb2222",
            "startsAt": "2026-08-29T10:01:00Z"
          }
        ]
      }
      """;

  private static final Instant START = Instant.parse("2026-08-29T10:00:00Z");

  /**
   * A source reading a clock stopped at {@code START}.
   *
   * <p>The source holds no state, so "the same source later" and "another source built at a later
   * moment" are the same thing — which is why the two tests that care about time build a second one
   * rather than reaching for a mutable clock.
   */
  private final GrafanaWebhookSource source = sourceAt(START);

  private static GrafanaWebhookSource sourceAt(final Instant when) {
    return new GrafanaWebhookSource(Clock.fixed(when, ZoneOffset.UTC));
  }

  @Test
  void namesItselfAsTheSourceAndPathSegment() {
    assertThat(source.name()).isEqualTo("grafana");
  }

  @Test
  void acceptsTheSecretAsABearerToken() {
    assertThat(source.verify(authorized("Bearer " + SECRET), SECRET)).isTrue();
    // The scheme is matched without regard to case, as HTTP requires of it.
    assertThat(source.verify(authorized("bearer " + SECRET), SECRET)).isTrue();
  }

  @Test
  void acceptsTheSecretAsABasicAuthPassword() {
    assertThat(source.verify(authorized(basic("grafana", SECRET)), SECRET)).isTrue();
    // The username is whatever the contact point was filled in with, and is not part of the secret.
    assertThat(source.verify(authorized(basic("", SECRET)), SECRET)).isTrue();
    assertThat(source.verify(authorized(basic("anyone-at-all", SECRET)), SECRET)).isTrue();
  }

  @Test
  void rejectsTheWrongCredential() {
    assertThat(source.verify(authorized("Bearer not-the-secret"), SECRET)).isFalse();
    assertThat(source.verify(authorized(basic("grafana", "not-the-secret")), SECRET)).isFalse();
    // A password matching only a prefix of the secret must fail like any other.
    assertThat(source.verify(authorized(basic("grafana", SECRET.substring(0, 10))), SECRET))
        .isFalse();
    // The secret as the username rather than the password is not the credential.
    assertThat(source.verify(authorized(basic(SECRET, "")), SECRET)).isFalse();
  }

  @Test
  void rejectsEverythingWhenNoSecretIsConfigured() {
    assertThat(source.verify(authorized("Bearer " + SECRET), null)).isFalse();
    assertThat(source.verify(authorized("Bearer " + SECRET), "")).isFalse();
    assertThat(source.verify(authorized("Bearer " + SECRET), "   ")).isFalse();
  }

  @Test
  void rejectsMalformedCredentialsWithoutThrowing() {
    final var malformed =
        new String[] {
          "", // no credential at all
          "Basic", // a scheme and nothing else
          "Basic !!!not-base64!!!",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("no-colon-here".getBytes(StandardCharsets.UTF_8)),
          "Bearer",
          "Digest username=\"grafana\"", // a scheme we do not accept
          SECRET // the secret with no scheme
        };

    for (final var header : malformed) {
      final var delivery = authorized(header);
      assertThatCode(() -> source.verify(delivery, SECRET)).doesNotThrowAnyException();
      assertThat(source.verify(delivery, SECRET)).as(header).isFalse();
    }
  }

  @Test
  void rejectsADeliveryWithNoAuthorizationHeader() {
    final var delivery = new WebhookDelivery(Map.of(), TWO_ALERTS.getBytes(StandardCharsets.UTF_8));

    assertThat(source.verify(delivery, SECRET)).isFalse();
  }

  @Test
  void readsOneObservationPerAlertInTheBatch() {
    final var observations = source.observations(delivery(TWO_ALERTS));

    assertThat(observations).hasSize(2);
    assertThat(observations).allSatisfy(it -> assertThat(it.source()).isEqualTo("grafana"));
    assertThat(observations.stream().map(it -> it.correlationKey()).toList())
        .containsExactly("grafana:aaaa1111", "grafana:bbbb2222");
    assertThat(observations.getFirst().kind()).isEqualTo("alert.firing");
    assertThat(observations.getFirst().title()).isEqualTo("DiskFull on db-1");
    assertThat(observations.getFirst().summary())
        .contains("DiskFull")
        .contains("critical")
        .contains("Disk is 97% full")
        .contains("2026-08-29T10:00:00Z");
    assertThat(observations.get(1).summary()).contains("40k messages waiting");
    assertThat(observations.getFirst().route().isEmpty()).isTrue();
  }

  @Test
  void keepsOnlyItsOwnAlertAsEvidence() {
    final var observations = source.observations(delivery(TWO_ALERTS));

    assertThat(observations.getFirst().payloadJson())
        .contains("aaaa1111")
        .doesNotContain("bbbb2222");
  }

  @Test
  void correlatesFiringAndResolvedOntoOneSituation() {
    final var firing =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":"firing","fingerprint":"aaaa1111",
                 "labels":{"alertname":"DiskFull"}}]}
                """));
    final var resolved =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":"resolved","fingerprint":"aaaa1111",
                 "labels":{"alertname":"DiskFull"}}]}
                """));

    assertThat(firing.getFirst().correlationKey()).isEqualTo(resolved.getFirst().correlationKey());
    assertThat(firing.getFirst().kind()).isEqualTo("alert.firing");
    assertThat(resolved.getFirst().kind()).isEqualTo("alert.resolved");
  }

  @Test
  void fallsBackToTheLabelsWhenThereIsNoFingerprint() {
    final var one =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":"firing",
                 "labels":{"alertname":"DiskFull","instance":"db-1"}}]}
                """));
    // The same labels in another order are the same alert: a JSON object has no order to rely on.
    final var reordered =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":"firing",
                 "labels":{"instance":"db-1","alertname":"DiskFull"}}]}
                """));
    final var other =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":"firing",
                 "labels":{"alertname":"DiskFull","instance":"db-2"}}]}
                """));

    assertThat(one.getFirst().correlationKey())
        .startsWith("grafana:labels:")
        .isEqualTo(reordered.getFirst().correlationKey())
        .isNotEqualTo(other.getFirst().correlationKey());
  }

  @Test
  void takesTheStatusFromTheBatchWhenTheAlertDoesNotSayIt() {
    final var observations =
        source.observations(
            delivery(
                """
                {"status":"resolved","alerts":[{"fingerprint":"aaaa1111"}]}
                """));

    assertThat(observations.getFirst().kind()).isEqualTo("alert.resolved");
  }

  @Test
  void foldsARetryOfTheSameBodyIntoOneDeliveryWithinTheMinute() {
    // Grafana's own retry of a failed notification arrives within seconds of the attempt, so it has
    // to be recognised as the same delivery rather than counted as a second alert.
    final var delivery = delivery(TWO_ALERTS);

    final var first = deliveryIds(source, delivery);
    final var second = deliveryIds(sourceAt(START.plus(Duration.ofSeconds(5))), delivery);

    assertThat(first).isEqualTo(second);
    // And the alerts of one batch keep identities of their own, or a delivery of thirty would
    // collapse into one observation.
    assertThat(first).doesNotHaveDuplicates().hasSize(2);
  }

  @Test
  void admitsARepeatNotificationOfTheSameAlertAsNews() {
    // The other half of the trade-off, and the reason the window is a minute rather than for ever:
    // Grafana re-notifies on repeat_interval, measured in minutes at least and usually hours.
    // "Still
    // firing an hour later" is information, so it must not be folded away as a duplicate.
    final var delivery = delivery(TWO_ALERTS);

    final var first = deliveryIds(source, delivery);
    final var later = deliveryIds(sourceAt(START.plus(Duration.ofHours(1))), delivery);

    assertThat(later).doesNotContainAnyElementsOf(first);
  }

  private static List<String> deliveryIds(
      final GrafanaWebhookSource source, final WebhookDelivery delivery) {
    return source.observations(delivery).stream().map(Observation::deliveryId).toList();
  }

  @Test
  void givesADifferentDeliveryIdToADifferentBody() {
    final var first = source.observations(delivery(TWO_ALERTS)).getFirst().deliveryId();
    final var second =
        source.observations(delivery(TWO_ALERTS.replace("97%", "98%"))).getFirst().deliveryId();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void yieldsNothingForADeliveryWithNoAlerts() {
    assertThat(source.observations(delivery("{\"status\":\"firing\",\"alerts\":[]}"))).isEmpty();
    assertThat(source.observations(delivery("{\"status\":\"firing\"}"))).isEmpty();
    assertThat(source.observations(delivery("{\"alerts\":\"not an array\"}"))).isEmpty();
    assertThat(source.observations(delivery("[]"))).isEmpty();
  }

  @Test
  void skipsBatchElementsThatAreNotAlerts() {
    final var observations =
        source.observations(
            delivery(
                """
                {"alerts":["a string",7,null,{"fingerprint":"aaaa1111"}]}
                """));

    assertThat(observations).hasSize(1);
    assertThat(observations.getFirst().correlationKey()).isEqualTo("grafana:aaaa1111");
  }

  @Test
  void yieldsNothingForMalformedJsonWithoutThrowing() {
    for (final var body : new String[] {"", "   ", "{", "{\"alerts\":[", "not json"}) {
      final var delivery = delivery(body);
      assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
      assertThat(source.observations(delivery)).as(body).isEmpty();
    }
  }

  @Test
  void survivesAPayloadNestedFarDeeperThanAnythingGrafanaSends() {
    final var delivery = delivery("[".repeat(5000) + "]".repeat(5000));

    assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
    assertThat(source.observations(delivery)).isEmpty();
  }

  @Test
  void survivesFieldsOfTheWrongType() {
    final var observations =
        source.observations(
            delivery(
                """
                {"alerts":[{"status":{"nested":true},
                 "labels":{"alertname":["DiskFull"],"instance":7,"severity":null},
                 "annotations":"not an object",
                 "fingerprint":{"not":"a string"}}]}
                """));

    assertThat(observations).hasSize(1);
    final var observation = observations.getFirst();
    // No label read as text, no fingerprint read as text: it correlates as an alert nothing
    // identifies rather than on structure the payload put where a name was due.
    assertThat(observation.correlationKey()).isEqualTo("grafana:unidentified");
    assertThat(observation.kind()).isEqualTo("alert.firing");
    assertThat(observation.title()).isEqualTo("alert");
  }

  private WebhookDelivery delivery(final String body) {
    return new WebhookDelivery(
        Map.of("Authorization", "Bearer " + SECRET), body.getBytes(StandardCharsets.UTF_8));
  }

  private WebhookDelivery authorized(final String header) {
    return new WebhookDelivery(
        Map.of("Authorization", header), TWO_ALERTS.getBytes(StandardCharsets.UTF_8));
  }

  private String basic(final String user, final String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }
}
