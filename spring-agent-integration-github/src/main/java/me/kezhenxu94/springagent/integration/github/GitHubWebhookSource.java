package me.kezhenxu94.springagent.integration.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.Actor;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub's repository webhooks: an HMAC over the raw body, a delivery UUID GitHub mints itself, and
 * a payload whose shape depends on which event it is.
 *
 * <p>The correlation key is the only judgement here, and it is what decides whether the agent sees
 * one situation about a pull request or twelve unrelated ones. GitHub sends a separate delivery for
 * every step of the same conversation — the PR opens, a review comes back, a check fails, someone
 * comments — and they only become one thing to have an opinion about if they collapse onto the same
 * key. So the key is the most specific thing in the payload that names the subject rather than the
 * event: the issue or pull request number where there is one, the workflow where the event is about
 * a run of it, and only failing both, the repository and the event kind, which at least keeps
 * pushes to one repository from being confused with pushes to another.
 */
@Slf4j
public class GitHubWebhookSource implements WebhookSource {

  private static final String NAME = "github";

  /**
   * Its own mapper, for the reason {@code StringMapJsonConverter} keeps one: how a hostile payload
   * is read must not drift with whatever modules or configuration a shared bean picks up elsewhere
   * in the application.
   */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
  private static final String DELIVERY_HEADER = "X-GitHub-Delivery";
  private static final String EVENT_HEADER = "X-GitHub-Event";

  /**
   * The only prefix GitHub sends today. {@code X-Hub-Signature} (SHA-1) is deliberately not
   * accepted even though GitHub still sends it alongside: accepting the weaker of two signatures is
   * the same as only having the weaker one, since an attacker picks which header to forge.
   */
  private static final String SIGNATURE_PREFIX = "sha256=";

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  /** Hex of a SHA-256 HMAC, and so the only length a genuine signature has. */
  private static final int SIGNATURE_HEX_LENGTH = 64;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean verify(final WebhookDelivery delivery, final String secret) {
    if (secret == null || secret.isBlank()) {
      // A source with no secret refuses everything. The alternative — accepting whatever arrives
      // when nobody configured a secret — turns a forgotten line of configuration into an open door
      // that wakes the agent on anyone's say-so.
      log.debug("Refusing a {} delivery: no secret is configured for this source", NAME);
      return false;
    }
    final var header = delivery.header(SIGNATURE_HEADER);
    if (header == null || !header.startsWith(SIGNATURE_PREFIX)) {
      log.debug("Refusing a {} delivery: no usable {} header", NAME, SIGNATURE_HEADER);
      return false;
    }
    final var hex = header.substring(SIGNATURE_PREFIX.length());
    if (hex.length() != SIGNATURE_HEX_LENGTH) {
      // Checked before parsing, because HexFormat throws on odd lengths and this is reached by
      // unauthenticated traffic. Comparing lengths reveals nothing a forger does not already know.
      log.debug(
          "Refusing a {} delivery: signature is not {} hex characters", NAME, SIGNATURE_HEX_LENGTH);
      return false;
    }
    final byte[] presented;
    try {
      presented = HexFormat.of().parseHex(hex);
    } catch (final IllegalArgumentException e) {
      log.debug("Refusing a {} delivery: signature is not hex", NAME);
      return false;
    }
    final byte[] expected;
    try {
      final var mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      expected = mac.doFinal(delivery.body());
    } catch (final NoSuchAlgorithmException | java.security.InvalidKeyException e) {
      // Neither can happen with a fixed algorithm and a non-empty key, but verify must not throw:
      // whatever the reason we cannot compute a signature, we have not authenticated the request.
      log.warn("Could not compute a {} signature; refusing the delivery", NAME, e);
      return false;
    }
    // isEqual and not Arrays.equals or String.equals: those return at the first differing byte,
    // which times how much of a guess was right and lets a caller extend a signature one byte at
    // a time.
    return MessageDigest.isEqual(expected, presented);
  }

  @Override
  public Optional<Observation> observation(final WebhookDelivery delivery) {
    final var event = delivery.header(EVENT_HEADER);
    if (event == null || event.isBlank()) {
      log.debug("Ignoring a {} delivery with no {} header", NAME, EVENT_HEADER);
      return Optional.empty();
    }
    if ("ping".equalsIgnoreCase(event)) {
      // What GitHub sends when the hook is saved, to prove the endpoint answers. It says nothing
      // happened, so recording it would open a situation about the act of configuring the hook.
      return Optional.empty();
    }
    final var body = delivery.bodyAsText();
    final JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (final JacksonException e) {
      // Authentic but unreadable. The signature checked out, so this is GitHub sending something we
      // cannot parse rather than an attack; either way there is nothing to correlate on.
      log.warn("Could not parse an authentic {} payload for event '{}'", NAME, event, e);
      return Optional.empty();
    }

    if (!root.isObject()) {
      // Not a webhook payload at all: an empty body parses to a missing node rather than
      // failing, and a bare array or string carries nothing to correlate on. Refused here rather
      // than left to fall through, which would mint a situation keyed on the event kind alone for
      // a body that said nothing.
      log.debug("Ignoring a {} delivery whose body is not a JSON object", NAME);
      return Optional.empty();
    }

    final var action = text(root, "action");
    // "issues" alone is not what a policy wants to distinguish; "issues.opened" against
    // "issues.labeled" is. Reported in GitHub's own vocabulary rather than mapped to ours, so that
    // what a deployment writes in configuration is what it reads in GitHub's documentation.
    final var kind = action == null ? event : event + "." + action;

    final var repository = text(root.path("repository"), "full_name");
    final var number = number(root);
    final var workflow = workflowName(root);

    return Optional.of(
        Observation.builder()
            .source(NAME)
            .deliveryId(deliveryId(delivery))
            .kind(kind)
            .correlationKey(correlationKey(repository, number, workflow, event))
            .title(title(repository, number, workflow, kind))
            .summary(summary(root, kind, repository, number))
            // Authenticated, not merely claimed, because here it is: verify() has covered the whole
            // body with GitHub's HMAC by the time this runs, so sender.login is a name GitHub
            // attests to rather than one the payload merely contains. That is the difference
            // Actor.authenticated turns on, and it is what makes a trusted-actors list for this
            // source a real check rather than a formality.
            .actor(Actor.authenticated(actor(root)))
            .payloadJson(body)
            // observedAt is left to default to now on purpose. The payload's timestamps
            // (created_at, updated_at) belong to the object the event is about, not to the event: a
            // comment on a year-old issue carries that issue's created_at, and reporting it as when
            // this was observed would age the situation out of the open set the moment it arrived.
            .build());
  }

  /**
   * GitHub's own delivery UUID, which is the same across the redeliveries it performs after a
   * timeout and different for a genuinely new event — exactly what the idempotency key has to mean.
   * The body hash is only a fallback for a delivery that reached us without the header, where
   * treating identical bodies as one delivery is the closest thing to GitHub's guarantee available.
   */
  private String deliveryId(final WebhookDelivery delivery) {
    final var id = delivery.header(DELIVERY_HEADER);
    if (id != null && !id.isBlank()) {
      return id;
    }
    log.debug(
        "A {} delivery carried no {}; falling back to a hash of the body", NAME, DELIVERY_HEADER);
    return NAME + ":body:" + sha256Hex(delivery.body());
  }

  private String correlationKey(
      final String repository, final Integer number, final String workflow, final String event) {
    if (repository == null || repository.isBlank()) {
      // Every event GitHub sends about a repository names it, so this is the organisation-level and
      // marketplace payloads. They still correlate by kind, which is enough for a situation like
      // "somebody keeps changing the org's members".
      return NAME + ":" + event;
    }
    if (number != null) {
      return NAME + ":" + repository + "#" + number;
    }
    if (workflow != null && !workflow.isBlank()) {
      return NAME + ":" + repository + ":workflow:" + workflow;
    }
    return NAME + ":" + repository + ":" + event;
  }

  /**
   * The issue or pull request this event is about. Both keys appear on payloads for events that are
   * neither — a review comment carries {@code pull_request}, an issue comment carries {@code issue}
   * — which is the point: those are exactly the events that must land on the same key as the thing
   * they are about rather than on one of their own.
   */
  private Integer number(final JsonNode root) {
    for (final var field : List.of("issue", "pull_request", "discussion")) {
      final var value = root.path(field).path("number");
      if (value.isIntegralNumber()) {
        return value.asInt();
      }
    }
    // On a review event the number lives one level down under the pull request; on
    // check_run/check_suite payloads there may be several, and then there is no single subject to
    // correlate on, so those fall through to the workflow or repository key.
    final var direct = root.path("number");
    return direct.isIntegralNumber() ? direct.asInt() : null;
  }

  /** The workflow a {@code workflow_run} or {@code workflow_job} event is about. */
  private String workflowName(final JsonNode root) {
    final var run = text(root.path("workflow_run"), "name");
    if (run != null) {
      return run;
    }
    final var job = text(root.path("workflow_job"), "workflow_name");
    if (job != null) {
      return job;
    }
    return text(root.path("workflow"), "name");
  }

  private String title(
      final String repository, final Integer number, final String workflow, final String kind) {
    if (repository == null || repository.isBlank()) {
      return "GitHub " + kind;
    }
    if (number != null) {
      return repository + "#" + number;
    }
    if (workflow != null && !workflow.isBlank()) {
      return repository + " workflow " + workflow;
    }
    return repository + " " + kind;
  }

  /** Who GitHub says caused the event, for {@link Observation#actor()}. */
  private static String actor(final JsonNode root) {
    return text(root.path("sender"), "login");
  }

  /**
   * One line a human would read first. Who caused the event is in here as well as in {@link
   * Observation#actor()}, and the two are not the same thing said twice: this is the evidence a
   * reader wants first, shown to the model like the rest of the payload, while the other is a fact
   * about the delivery that decides whether there is a run to show it to. Neither is the identity a
   * triage run acts as, which is never the identity of whoever tripped it.
   */
  private String summary(
      final JsonNode root, final String kind, final String repository, final Integer number) {
    final var subject = new StringBuilder(kind);
    if (repository != null && !repository.isBlank()) {
      subject.append(" in ").append(repository);
      if (number != null) {
        subject.append('#').append(number);
      }
    }
    final var headline =
        firstText(
            text(root.path("issue"), "title"),
            text(root.path("pull_request"), "title"),
            text(root.path("discussion"), "title"),
            text(root.path("workflow_run"), "conclusion"),
            text(root.path("workflow_job"), "conclusion"),
            text(root.path("release"), "name"));
    if (headline != null) {
      subject.append(": ").append(headline);
    }
    final var actor = actor(root);
    if (actor != null) {
      subject.append(" (by ").append(actor).append(')');
    }
    return subject.toString();
  }

  private static String firstText(final String... candidates) {
    for (final var candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * A field read as text only where it really is text. {@code asString} on a node that is an object
   * or a number would hand a payload's author a way to put arbitrary structure where a name is
   * expected, and {@code path} keeps an absent parent from being an exception.
   */
  private static String text(final JsonNode node, final String field) {
    final var value = node.path(field);
    return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
