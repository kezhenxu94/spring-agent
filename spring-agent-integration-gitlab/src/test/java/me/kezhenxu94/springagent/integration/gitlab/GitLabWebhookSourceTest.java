package me.kezhenxu94.springagent.integration.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import org.junit.jupiter.api.Test;

class GitLabWebhookSourceTest {

  private static final String SECRET = "gitlab-shared-token";

  private final GitLabWebhookSource source = new GitLabWebhookSource();

  @Test
  void namesItselfAsTheSourceAndPathSegment() {
    assertThat(source.name()).isEqualTo("gitlab");
  }

  @Test
  void acceptsTheConfiguredToken() {
    assertThat(source.verify(withToken(SECRET), SECRET)).isTrue();
  }

  @Test
  void rejectsAnyOtherToken() {
    assertThat(source.verify(withToken("gitlab-shared-toke"), SECRET)).isFalse();
    assertThat(source.verify(withToken("gitlab-shared-tokenn"), SECRET)).isFalse();
    assertThat(source.verify(withToken("GITLAB-SHARED-TOKEN"), SECRET)).isFalse();
    // Whitespace is not trimmed: a token is compared as the bytes it arrived as, and accepting a
    // padded one would mean two different headers authenticate.
    assertThat(source.verify(withToken(" " + SECRET), SECRET)).isFalse();
    assertThat(source.verify(withToken(""), SECRET)).isFalse();
  }

  @Test
  void rejectsEverythingWhenNoSecretIsConfigured() {
    assertThat(source.verify(withToken(SECRET), null)).isFalse();
    assertThat(source.verify(withToken(SECRET), "")).isFalse();
    assertThat(source.verify(withToken(SECRET), "  ")).isFalse();
  }

  @Test
  void rejectsADeliveryWithNoTokenHeader() {
    final var delivery = new WebhookDelivery(Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> source.verify(delivery, SECRET)).doesNotThrowAnyException();
    assertThat(source.verify(delivery, SECRET)).isFalse();
  }

  @Test
  void matchesTheTokenHeaderWhateverCaseAProxyUsed() {
    final var delivery =
        new WebhookDelivery(
            Map.of("x-gitlab-token", SECRET), "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.verify(delivery, SECRET)).isTrue();
  }

  @Test
  void readsAnIssueEventAsOneObservation() {
    final var observations =
        source.observations(
            delivery(
                "Issue Hook",
                "b1e2c3d4-0000-4000-8000-000000000001",
                """
                {
                  "object_kind": "issue",
                  "project": {"path_with_namespace": "acme/widgets"},
                  "object_attributes": {"iid": 7, "action": "open", "title": "Disk is full"},
                  "user": {"username": "tanuki"}
                }
                """));

    assertThat(observations).hasSize(1);
    final var observation = observations.getFirst();
    assertThat(observation.source()).isEqualTo("gitlab");
    assertThat(observation.deliveryId()).isEqualTo("b1e2c3d4-0000-4000-8000-000000000001");
    assertThat(observation.kind()).isEqualTo("issue.open");
    assertThat(observation.correlationKey()).isEqualTo("gitlab:acme/widgets!7");
    assertThat(observation.title()).isEqualTo("acme/widgets!7");
    assertThat(observation.summary()).contains("Disk is full").contains("tanuki");
    assertThat(observation.payloadJson()).contains("\"iid\": 7");
    assertThat(observation.route().isEmpty()).isTrue();
  }

  @Test
  void groupsEventsAboutOneMergeRequestAndSeparatesDifferentOnes() {
    final var opened =
        source.observations(
            delivery(
                "Merge Request Hook",
                "a",
                """
                {"object_kind":"merge_request","project":{"path_with_namespace":"acme/widgets"},
                 "object_attributes":{"iid":3,"action":"open","title":"Cache the thing"}}
                """));
    final var commented =
        source.observations(
            delivery(
                "Note Hook",
                "b",
                """
                {"object_kind":"note","project":{"path_with_namespace":"acme/widgets"},
                 "object_attributes":{"note":"looks wrong"},
                 "merge_request":{"iid":3,"title":"Cache the thing"}}
                """));
    final var another =
        source.observations(
            delivery(
                "Merge Request Hook",
                "c",
                """
                {"object_kind":"merge_request","project":{"path_with_namespace":"acme/widgets"},
                 "object_attributes":{"iid":4,"action":"open"}}
                """));

    assertThat(opened.getFirst().correlationKey())
        .isEqualTo(commented.getFirst().correlationKey())
        .isNotEqualTo(another.getFirst().correlationKey());
    assertThat(commented.getFirst().kind()).isEqualTo("note");
  }

  @Test
  void separatesTheSameIidInDifferentProjects() {
    final var here =
        source.observations(
            delivery(
                "Issue Hook",
                "a",
                """
                {"object_kind":"issue","project":{"path_with_namespace":"acme/widgets"},
                 "object_attributes":{"iid":1}}
                """));
    final var there =
        source.observations(
            delivery(
                "Issue Hook",
                "b",
                """
                {"object_kind":"issue","project":{"path_with_namespace":"acme/gadgets"},
                 "object_attributes":{"iid":1}}
                """));

    assertThat(here.getFirst().correlationKey()).isNotEqualTo(there.getFirst().correlationKey());
  }

  @Test
  void fallsBackToProjectAndKindWithoutAnIid() {
    final var pipeline =
        source.observations(
            delivery(
                "Pipeline Hook",
                "a",
                """
                {"object_kind":"pipeline","project":{"path_with_namespace":"acme/widgets"},
                 "object_attributes":{"status":"failed"}}
                """));

    assertThat(pipeline.getFirst().correlationKey()).isEqualTo("gitlab:acme/widgets:pipeline");
    assertThat(pipeline.getFirst().summary()).contains("failed");
  }

  @Test
  void takesTheKindFromTheHeaderWhenTheBodyDoesNotSayIt() {
    final var observations =
        source.observations(
            delivery(
                "Merge Request Hook",
                "a",
                """
                {"project":{"path_with_namespace":"acme/widgets"},"object_attributes":{"iid":3}}
                """));

    // The header's "Merge Request Hook" and the body's "merge_request" must not become two
    // different kinds a deployment has to configure separately.
    assertThat(observations.getFirst().kind()).isEqualTo("merge_request");
  }

  @Test
  void ignoresADeliveryThatNamesNoKind() {
    final var delivery =
        new WebhookDelivery(Map.of(), "{\"whatever\":1}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.observations(delivery)).isEmpty();
  }

  @Test
  void yieldsNothingForMalformedJsonWithoutThrowing() {
    for (final var body : new String[] {"", "  ", "{", "]", "not json", "{\"object_kind\":"}) {
      final var delivery = delivery("Issue Hook", "a", body);
      assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
      assertThat(source.observations(delivery)).as(body).isEmpty();
    }
  }

  @Test
  void survivesAPayloadNestedFarDeeperThanAnythingGitLabSends() {
    final var delivery = delivery("Issue Hook", "a", "[".repeat(5000) + "]".repeat(5000));

    assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
    assertThat(source.observations(delivery)).isEmpty();
  }

  @Test
  void survivesFieldsOfTheWrongType() {
    final var observations =
        source.observations(
            delivery(
                "Issue Hook",
                "a",
                """
                {"object_kind":["issue"],
                 "project":{"path_with_namespace":{"nested":true}},
                 "object_attributes":{"iid":"seven","action":42}}
                """));

    assertThat(observations).hasSize(1);
    assertThat(observations.getFirst().kind()).isEqualTo("issue");
    assertThat(observations.getFirst().correlationKey()).isEqualTo("gitlab:issue");
  }

  @Test
  void derivesAStableDeliveryIdWhenGitLabSentNoEventUuid() {
    final var body =
        """
        {"object_kind":"issue","project":{"path_with_namespace":"acme/widgets"},
         "object_attributes":{"iid":7}}
        """;
    final var delivery =
        new WebhookDelivery(
            Map.of("X-Gitlab-Event", "Issue Hook"), body.getBytes(StandardCharsets.UTF_8));

    String first;
    String second;
    long before;
    long after;
    do {
      // The derived identity is bucketed by the minute, so a boundary crossed between the two calls
      // would make them differ for a reason that is the point of the design rather than a fault.
      // Measured again in that case, which happens at most once.
      before = Instant.now().getEpochSecond() / 60;
      first = source.observations(delivery).getFirst().deliveryId();
      second = source.observations(delivery).getFirst().deliveryId();
      after = Instant.now().getEpochSecond() / 60;
    } while (before != after);

    assertThat(first).isNotBlank().isEqualTo(second);

    final var different =
        new WebhookDelivery(
            Map.of("X-Gitlab-Event", "Issue Hook"),
            body.replace("\"iid\":7", "\"iid\":8").getBytes(StandardCharsets.UTF_8));
    assertThat(source.observations(different).getFirst().deliveryId()).isNotEqualTo(first);
  }

  private WebhookDelivery withToken(final String token) {
    final var headers = new HashMap<String, String>();
    headers.put("X-Gitlab-Token", token);
    return new WebhookDelivery(headers, "{}".getBytes(StandardCharsets.UTF_8));
  }

  private WebhookDelivery delivery(final String event, final String uuid, final String body) {
    return new WebhookDelivery(
        Map.of("X-Gitlab-Event", event, "X-Gitlab-Event-UUID", uuid),
        body.getBytes(StandardCharsets.UTF_8));
  }
}
