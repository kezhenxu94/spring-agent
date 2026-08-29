package me.kezhenxu94.springagent.integration.grafana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import org.junit.jupiter.api.Test;

class GrafanaWebhookSourceTest {

  private static final String SECRET = "grafana-contact-point-secret";

  private static final String TWO_ALERTS =
      """
      {
        "status": "firing",
        "groupKey": "{}/{alertname=\\"DiskFull\\"}:{}",
        "groupLabels": {"alertname": "DiskFull"},
        "commonLabels": {"env": "prod"},
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
  void readsTheWholeBatchAsOneObservation() {
    // Grafana decided what belongs together from the contact point's group_by and posted the group.
    // Taking it apart would ask the agent for a separate opinion on each of thirty things it was
    // told are one.
    final var observation = source.observation(delivery(TWO_ALERTS)).orElseThrow();

    assertThat(observation.source()).isEqualTo("grafana");
    assertThat(observation.kind()).isEqualTo("alert.firing");
    // Correlated on the group Grafana named, not on one alert's fingerprint.
    assertThat(observation.correlationKey()).startsWith("grafana:group:");
    assertThat(observation.title()).contains("DiskFull").contains("2 alerts");
    // A webhook knows nowhere to talk; the route comes from configuration.
    assertThat(observation.route().isEmpty()).isTrue();
  }

  @Test
  void summarisesEveryAlertInTheBatch() {
    // The summary is what goes into the brief a triage run is given, so both alerts have to be in
    // it — with the batch split into observations this was one alert each and the agent saw one.
    final var summary = source.observation(delivery(TWO_ALERTS)).orElseThrow().summary();

    assertThat(summary).contains("2 alerts firing");
    assertThat(summary).contains("DiskFull").contains("db-1").contains("critical");
    assertThat(summary).contains("Disk is 97% full").contains("2026-08-29T10:00:00Z");
    assertThat(summary).contains("QueueBacklog").contains("40k messages waiting");
  }

  @Test
  void keepsTheWholeDeliveryAsEvidence() {
    // Once, rather than once per alert. Storing the batch against each of thirty observations was
    // thirty copies of the same evidence.
    final var observation = source.observation(delivery(TWO_ALERTS)).orElseThrow();

    assertThat(observation.payloadJson()).contains("aaaa1111").contains("bbbb2222");
  }

  @Test
  void saysHowManyMoreThereAreRatherThanNamingHundreds() {
    final var alerts = new StringBuilder();
    for (var i = 0; i < 12; i++) {
      alerts
          .append(i == 0 ? "" : ",")
          .append("{\"labels\":{\"alertname\":\"Alert")
          .append(i)
          .append("\"}}");
    }
    final var summary =
        source
            .observation(delivery("{\"status\":\"firing\",\"alerts\":[" + alerts + "]}"))
            .orElseThrow()
            .summary();

    assertThat(summary).contains("12 alerts firing");
    assertThat(summary).contains("Alert0").contains("Alert4");
    // What was left out is said rather than implied; the rest are in the payload.
    assertThat(summary).contains("and 7 more");
    assertThat(summary).doesNotContain("Alert11");
  }

  @Test
  void saysWhenGrafanaItselfTruncatedTheBatch() {
    final var summary =
        source
            .observation(
                delivery(
                    "{\"status\":\"firing\",\"truncatedAlerts\":40,"
                        + "\"alerts\":[{\"labels\":{\"alertname\":\"DiskFull\"}}]}"))
            .orElseThrow()
            .summary();

    assertThat(summary).contains("40 more truncated by Grafana");
  }

  @Test
  void correlatesFiringAndResolvedOntoOneSituation() {
    // The same group coming back, whatever it now says: that is what makes "this has been going on
    // for twenty minutes" and "it cleared" answers about one thing rather than two situations.
    final var firing =
        source.observation(
            delivery(
                """
                {"status":"firing","groupKey":"disk-full-group",
                 "alerts":[{"status":"firing","fingerprint":"aaaa1111"}]}
                """));
    final var resolved =
        source.observation(
            delivery(
                """
                {"status":"resolved","groupKey":"disk-full-group",
                 "alerts":[{"status":"resolved","fingerprint":"aaaa1111"}]}
                """));

    assertThat(firing.orElseThrow().correlationKey())
        .isEqualTo(resolved.orElseThrow().correlationKey());
    assertThat(firing.orElseThrow().kind()).isEqualTo("alert.firing");
    assertThat(resolved.orElseThrow().kind()).isEqualTo("alert.resolved");
  }

  @Test
  void separatesOneGroupFromAnother() {
    final var disk =
        source.observation(delivery("{\"groupKey\":\"a\",\"alerts\":[{}]}")).orElseThrow();
    final var queue =
        source.observation(delivery("{\"groupKey\":\"b\",\"alerts\":[{}]}")).orElseThrow();

    assertThat(disk.correlationKey()).isNotEqualTo(queue.correlationKey());
  }

  @Test
  void hashesAGroupKeyTooLongForTheColumnItLandsIn() {
    // A group key spells out every label it grouped on and has no bound; correlationKey does.
    final var key = "x".repeat(500);
    final var observation =
        source
            .observation(delivery("{\"groupKey\":\"" + key + "\",\"alerts\":[{}]}"))
            .orElseThrow();

    assertThat(observation.correlationKey()).hasSizeLessThan(120).doesNotContain(key);
  }

  @Test
  void fallsBackToTheGroupLabelsWhenThereIsNoGroupKey() {
    // An older Grafana sends the labels it grouped on but no key made from them.
    final var one =
        source.observation(
            delivery(
                """
                {"status":"firing","groupLabels":{"alertname":"DiskFull","instance":"db-1"},
                 "alerts":[{}]}
                """));
    // The same labels in another order are the same group: a JSON object has no order to rely on.
    final var reordered =
        source.observation(
            delivery(
                """
                {"status":"firing","groupLabels":{"instance":"db-1","alertname":"DiskFull"},
                 "alerts":[{}]}
                """));
    final var other =
        source.observation(
            delivery(
                """
                {"status":"firing","groupLabels":{"alertname":"DiskFull","instance":"db-2"},
                 "alerts":[{}]}
                """));

    assertThat(one.orElseThrow().correlationKey())
        .startsWith("grafana:group:")
        .isEqualTo(reordered.orElseThrow().correlationKey())
        .isNotEqualTo(other.orElseThrow().correlationKey());
  }

  @Test
  void collapsesDeliveriesWithNothingToGroupOnRatherThanInventingAKeyEach() {
    // Saying so is better than a situation per delivery: they become one situation about a source
    // sending alerts nobody can identify.
    final var one = source.observation(delivery("{\"alerts\":[{}]}")).orElseThrow();
    final var two = source.observation(delivery("{\"alerts\":[{},{}]}")).orElseThrow();

    assertThat(one.correlationKey()).isEqualTo("grafana:ungrouped").isEqualTo(two.correlationKey());
  }

  @Test
  void takesTheStatusOfTheGroup() {
    // The group's status is the delivery's, since the delivery is now the subject.
    assertThat(
            source
                .observation(delivery("{\"status\":\"resolved\",\"alerts\":[{}]}"))
                .orElseThrow()
                .kind())
        .isEqualTo("alert.resolved");
    // And firing where it says nothing, which is what Grafana means by omitting it.
    assertThat(source.observation(delivery("{\"alerts\":[{}]}")).orElseThrow().kind())
        .isEqualTo("alert.firing");
  }

  @Test
  void foldsARetryOfTheSameBodyIntoOneDeliveryWithinTheMinute() {
    // Grafana's own retry of a failed notification arrives within seconds of the attempt, so it has
    // to be recognised as the same delivery rather than counted as a second alert.
    final var delivery = delivery(TWO_ALERTS);

    final var first = deliveryId(source, delivery);
    final var second = deliveryId(sourceAt(START.plus(Duration.ofSeconds(5))), delivery);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void admitsARepeatNotificationOfTheSameAlertAsNews() {
    // The other half of the trade-off, and the reason the window is a minute rather than for ever:
    // Grafana re-notifies on repeat_interval, measured in minutes at least and usually hours.
    // "Still
    // firing an hour later" is information, so it must not be folded away as a duplicate.
    final var delivery = delivery(TWO_ALERTS);

    final var first = deliveryId(source, delivery);
    final var later = deliveryId(sourceAt(START.plus(Duration.ofHours(1))), delivery);

    assertThat(later).isNotEqualTo(first);
  }

  private static String deliveryId(
      final GrafanaWebhookSource source, final WebhookDelivery delivery) {
    return source.observation(delivery).orElseThrow().deliveryId();
  }

  @Test
  void givesADifferentDeliveryIdToADifferentBody() {
    final var first = source.observation(delivery(TWO_ALERTS)).orElseThrow().deliveryId();
    final var second =
        source.observation(delivery(TWO_ALERTS.replace("97%", "98%"))).orElseThrow().deliveryId();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void yieldsNothingForADeliveryWithNoAlerts() {
    assertThat(source.observation(delivery("{\"status\":\"firing\",\"alerts\":[]}"))).isEmpty();
    assertThat(source.observation(delivery("{\"status\":\"firing\"}"))).isEmpty();
    assertThat(source.observation(delivery("{\"alerts\":\"not an array\"}"))).isEmpty();
    assertThat(source.observation(delivery("[]"))).isEmpty();
  }

  @Test
  void skipsBatchElementsThatAreNotAlerts() {
    // Still one observation, and the summary simply says nothing about the elements it could not
    // read rather than failing over them.
    final var observation =
        source
            .observation(
                delivery(
                    """
                    {"groupKey":"g","alerts":["a string",7,null,
                     {"labels":{"alertname":"DiskFull"}}]}
                    """))
            .orElseThrow();

    assertThat(observation.correlationKey()).isEqualTo("grafana:group:g");
    assertThat(observation.summary()).contains("DiskFull");
  }

  @Test
  void yieldsNothingForMalformedJsonWithoutThrowing() {
    for (final var body : new String[] {"", "   ", "{", "{\"alerts\":[", "not json"}) {
      final var delivery = delivery(body);
      assertThatCode(() -> source.observation(delivery)).doesNotThrowAnyException();
      assertThat(source.observation(delivery)).as(body).isEmpty();
    }
  }

  @Test
  void survivesAPayloadNestedFarDeeperThanAnythingGrafanaSends() {
    final var delivery = delivery("[".repeat(5000) + "]".repeat(5000));

    assertThatCode(() -> source.observation(delivery)).doesNotThrowAnyException();
    assertThat(source.observation(delivery)).isEmpty();
  }

  @Test
  void survivesFieldsOfTheWrongType() {
    final var observations =
        source.observation(
            delivery(
                """
                {"alerts":[{"status":{"nested":true},
                 "labels":{"alertname":["DiskFull"],"instance":7,"severity":null},
                 "annotations":"not an object",
                 "fingerprint":{"not":"a string"}}]}
                """));

    assertThat(observations).isPresent();
    final var observation = observations.orElseThrow();
    // No label read as text, no fingerprint read as text: it correlates as an alert nothing
    // identifies rather than on structure the payload put where a name was due.
    assertThat(observation.correlationKey()).isEqualTo("grafana:ungrouped");
    assertThat(observation.kind()).isEqualTo("alert.firing");
    assertThat(observation.title()).isEqualTo("1 Grafana alerts");
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
