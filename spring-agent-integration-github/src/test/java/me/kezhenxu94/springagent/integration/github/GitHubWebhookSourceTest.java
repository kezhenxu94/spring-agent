package me.kezhenxu94.springagent.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import org.junit.jupiter.api.Test;

/**
 * The signature half of these tests computes its own HMAC rather than asserting against a hex
 * string pasted in: a hardcoded digest tests that the implementation still does what it did, while
 * a computed one tests that it does what GitHub documents.
 */
class GitHubWebhookSourceTest {

  private static final String SECRET = "s3cr3t-webhook-token";

  private final GitHubWebhookSource source = new GitHubWebhookSource();

  @Test
  void namesItselfAsTheSourceAndPathSegment() {
    assertThat(source.name()).isEqualTo("github");
  }

  @Test
  void acceptsAGenuineSignature() {
    final var body = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(source.verify(signed(body, SECRET), SECRET)).isTrue();
  }

  @Test
  void rejectsABodyChangedAfterSigning() {
    final var signature = signature("{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8));
    final var tampered =
        new WebhookDelivery(
            Map.of("X-Hub-Signature-256", signature),
            "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.verify(tampered, SECRET)).isFalse();
  }

  @Test
  void rejectsASignatureMadeWithAnotherSecret() {
    final var body = "{}".getBytes(StandardCharsets.UTF_8);

    assertThat(source.verify(signed(body, "not-the-secret"), SECRET)).isFalse();
  }

  @Test
  void rejectsEverythingWhenNoSecretIsConfigured() {
    final var body = "{}".getBytes(StandardCharsets.UTF_8);

    assertThat(source.verify(signed(body, SECRET), null)).isFalse();
    assertThat(source.verify(signed(body, SECRET), "")).isFalse();
    assertThat(source.verify(signed(body, SECRET), "   ")).isFalse();
  }

  @Test
  void rejectsADeliveryWithNoSignature() {
    final var delivery = new WebhookDelivery(Map.of(), "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.verify(delivery, SECRET)).isFalse();
  }

  @Test
  void rejectsAnSha1SignatureEvenWhenItIsCorrect() {
    // GitHub still sends X-Hub-Signature beside the SHA-256 one. Accepting it would mean the
    // strength of the pair is the strength of the weaker, since a forger chooses the header.
    final var delivery =
        new WebhookDelivery(
            Map.of("X-Hub-Signature", "sha1=" + "0".repeat(40)),
            "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.verify(delivery, SECRET)).isFalse();
  }

  @Test
  void refusesMalformedSignaturesWithoutThrowing() {
    final var body = "{}".getBytes(StandardCharsets.UTF_8);
    final var malformed =
        new String[] {
          "sha256=" + "z".repeat(64), // right length, not hex
          "sha256=abc", // too short, and odd
          "sha256=", // nothing at all
          "sha256=" + "a".repeat(63), // odd length, which is what HexFormat throws on
          "sha256=" + "a".repeat(66),
          "garbage",
          ""
        };

    for (final var header : malformed) {
      final var delivery = new WebhookDelivery(Map.of("X-Hub-Signature-256", header), body);
      assertThatCode(() -> source.verify(delivery, SECRET)).doesNotThrowAnyException();
      assertThat(source.verify(delivery, SECRET)).as(header).isFalse();
    }
  }

  @Test
  void refusesAnEmptyBodyWithoutThrowing() {
    final var delivery =
        new WebhookDelivery(Map.of("X-Hub-Signature-256", "sha256=" + "a".repeat(64)), new byte[0]);

    assertThat(source.verify(delivery, SECRET)).isFalse();
  }

  @Test
  void readsAnIssueEventAsOneObservation() {
    final var observations =
        source.observations(
            delivery(
                "issues",
                "d290f1ee-6c54-4b01-90e6-d701748f0851",
                """
                {
                  "action": "opened",
                  "repository": {"full_name": "acme/widgets"},
                  "issue": {"number": 7, "title": "Disk is full"},
                  "sender": {"login": "octocat"}
                }
                """));

    assertThat(observations).hasSize(1);
    final var observation = observations.getFirst();
    assertThat(observation.source()).isEqualTo("github");
    assertThat(observation.deliveryId()).isEqualTo("d290f1ee-6c54-4b01-90e6-d701748f0851");
    assertThat(observation.kind()).isEqualTo("issues.opened");
    assertThat(observation.correlationKey()).isEqualTo("github:acme/widgets#7");
    assertThat(observation.title()).isEqualTo("acme/widgets#7");
    assertThat(observation.summary()).contains("Disk is full").contains("octocat");
    assertThat(observation.payloadJson()).contains("\"number\": 7");
    // A webhook knows nowhere to talk; the route comes from configuration.
    assertThat(observation.route().isEmpty()).isTrue();
    assertThat(observation.observedAt()).isNotNull();
  }

  @Test
  void groupsEventsAboutOneIssueAndSeparatesDifferentIssues() {
    final var opened =
        source.observations(
            delivery(
                "issues",
                "a",
                """
                {"action":"opened","repository":{"full_name":"acme/widgets"},"issue":{"number":7}}
                """));
    final var commented =
        source.observations(
            delivery(
                "issue_comment",
                "b",
                """
                {"action":"created","repository":{"full_name":"acme/widgets"},"issue":{"number":7}}
                """));
    final var another =
        source.observations(
            delivery(
                "issues",
                "c",
                """
                {"action":"opened","repository":{"full_name":"acme/widgets"},"issue":{"number":8}}
                """));

    assertThat(opened.getFirst().correlationKey())
        .isEqualTo(commented.getFirst().correlationKey())
        .isNotEqualTo(another.getFirst().correlationKey());
    // Same situation, different events: a policy distinguishes them by kind, not by key.
    assertThat(opened.getFirst().kind()).isEqualTo("issues.opened");
    assertThat(commented.getFirst().kind()).isEqualTo("issue_comment.created");
  }

  @Test
  void correlatesAReviewCommentWithItsPullRequest() {
    final var review =
        source.observations(
            delivery(
                "pull_request_review",
                "a",
                """
                {"action":"submitted","repository":{"full_name":"acme/widgets"},
                 "pull_request":{"number":42,"title":"Cache the thing"}}
                """));

    assertThat(review.getFirst().correlationKey()).isEqualTo("github:acme/widgets#42");
  }

  @Test
  void correlatesAWorkflowRunByWorkflowRatherThanByRun() {
    final var first =
        source.observations(
            delivery(
                "workflow_run",
                "a",
                """
                {"action":"completed","repository":{"full_name":"acme/widgets"},
                 "workflow_run":{"name":"CI","conclusion":"failure","id":1}}
                """));
    final var second =
        source.observations(
            delivery(
                "workflow_run",
                "b",
                """
                {"action":"completed","repository":{"full_name":"acme/widgets"},
                 "workflow_run":{"name":"CI","conclusion":"failure","id":2}}
                """));

    assertThat(first.getFirst().correlationKey()).isEqualTo("github:acme/widgets:workflow:CI");
    assertThat(second.getFirst().correlationKey()).isEqualTo(first.getFirst().correlationKey());
    assertThat(first.getFirst().summary()).contains("failure");
  }

  @Test
  void fallsBackToRepositoryAndKindWhenNothingNarrowerIsThere() {
    final var push =
        source.observations(
            delivery(
                "push",
                "a",
                """
                {"repository":{"full_name":"acme/widgets"},"ref":"refs/heads/main"}
                """));

    assertThat(push.getFirst().correlationKey()).isEqualTo("github:acme/widgets:push");
    assertThat(push.getFirst().kind()).isEqualTo("push");
  }

  @Test
  void fallsBackToTheKindAloneWhenThereIsNoRepository() {
    final var membership =
        source.observations(
            delivery(
                "organization",
                "a",
                """
                {"action":"member_added","organization":{"login":"acme"}}
                """));

    assertThat(membership.getFirst().correlationKey()).isEqualTo("github:organization");
  }

  @Test
  void skipsThePingThatOnlyProvesTheEndpointAnswers() {
    assertThat(source.observations(delivery("ping", "a", "{\"zen\":\"Keep it simple.\"}")))
        .isEmpty();
  }

  @Test
  void ignoresADeliveryWithNoEventHeader() {
    final var delivery =
        new WebhookDelivery(
            Map.of("X-GitHub-Delivery", "a"), "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(source.observations(delivery)).isEmpty();
  }

  @Test
  void yieldsNothingForMalformedJsonWithoutThrowing() {
    for (final var body : new String[] {"", "   ", "{", "not json at all", "[1,2", " "}) {
      final var delivery = delivery("issues", "a", body);
      assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
      assertThat(source.observations(delivery)).as(body).isEmpty();
    }
  }

  @Test
  void survivesAPayloadNestedFarDeeperThanAnythingGitHubSends() {
    // Jackson's own nesting limit is what stops this, and it reports it as a JacksonException like
    // any other parse failure — which is the whole reason the catch is on that and not on
    // JsonParseException.
    final var body = "[".repeat(5000) + "]".repeat(5000);
    final var delivery = delivery("issues", "a", body);

    assertThatCode(() -> source.observations(delivery)).doesNotThrowAnyException();
    assertThat(source.observations(delivery)).isEmpty();
  }

  @Test
  void survivesFieldsOfTheWrongType() {
    final var observations =
        source.observations(
            delivery(
                "issues",
                "a",
                """
                {"action":{"nested":"object"},
                 "repository":{"full_name":["not","a","string"]},
                 "issue":{"number":"seven","title":42},
                 "sender":7}
                """));

    // Nothing readable as a name is read as one, so this correlates by kind alone rather than on
    // whatever structure the payload put where a repository was due.
    assertThat(observations).hasSize(1);
    assertThat(observations.getFirst().kind()).isEqualTo("issues");
    assertThat(observations.getFirst().correlationKey()).isEqualTo("github:issues");
  }

  @Test
  void derivesADeliveryIdWhenGitHubSentNone() {
    final var body = "{\"repository\":{\"full_name\":\"acme/widgets\"}}";
    final var delivery =
        new WebhookDelivery(
            Map.of("X-GitHub-Event", "push"), body.getBytes(StandardCharsets.UTF_8));

    final var first = source.observations(delivery).getFirst().deliveryId();
    final var second = source.observations(delivery).getFirst().deliveryId();

    assertThat(first).isNotBlank().isEqualTo(second);
  }

  private WebhookDelivery delivery(final String event, final String id, final String body) {
    return new WebhookDelivery(
        Map.of("X-GitHub-Event", event, "X-GitHub-Delivery", id),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private WebhookDelivery signed(final byte[] body, final String secret) {
    return new WebhookDelivery(Map.of("X-Hub-Signature-256", hmac(body, secret)), body);
  }

  private String signature(final byte[] body) {
    return hmac(body, SECRET);
  }

  private String hmac(final byte[] body, final String secret) {
    try {
      final var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
