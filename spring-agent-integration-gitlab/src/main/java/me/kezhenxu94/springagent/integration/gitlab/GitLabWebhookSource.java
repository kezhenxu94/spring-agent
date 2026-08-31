package me.kezhenxu94.springagent.integration.gitlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.Actor;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitLab's project and group webhooks.
 *
 * <p>GitLab authenticates with a shared secret token echoed in a header rather than with a
 * signature over the body, which is weaker in a way worth writing down: the token is the same on
 * every delivery, so anyone who sees one request has everything needed to forge the next, and
 * nothing ties the token to the body it arrived with. There is no fixing that from this side —
 * GitLab offers no body signature — so the receiver's TLS is what the secrecy of the token rests
 * on, and the token must not be shared with a source that has a real signature.
 *
 * <p>What can be got right here is the comparison, which is constant-time over the bytes. A {@code
 * String.equals} would return at the first differing character and let a patient caller recover the
 * token one character at a time, which for a static token is a total compromise rather than the
 * per-delivery nuisance it would be for an HMAC.
 *
 * <p>That weakness carries through to the actor this reports, and the difference from GitHub is
 * worth knowing before trusting it. GitHub's actor sits inside a body its HMAC covers, so forging
 * one means forging the signature; GitLab's token says nothing about the body it arrived with, so
 * whoever holds the token writes {@code user.username} as freely as the rest of the payload. A
 * {@code trusted-actors} list here is therefore only ever as strong as the token's secrecy — it
 * distinguishes the colleagues using a GitLab this deployment trusts, and it does not withstand
 * somebody who has the token.
 */
@Slf4j
public class GitLabWebhookSource implements WebhookSource {

  private static final String NAME = "gitlab";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String TOKEN_HEADER = "X-Gitlab-Token";
  private static final String DELIVERY_HEADER = "X-Gitlab-Event-UUID";
  private static final String EVENT_HEADER = "X-Gitlab-Event";

  /**
   * The window a redelivery is folded into when GitLab sent no event UUID. See {@code
   * GrafanaWebhookSource} for why the identity is bucketed by time rather than by content alone.
   */
  private static final long DELIVERY_BUCKET_SECONDS = 60;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean verify(final WebhookDelivery delivery, final String secret) {
    if (secret == null || secret.isBlank()) {
      // No secret means refuse everything, not accept everything: an unconfigured source is not
      // a way to wake the agent.
      log.debug("Refusing a {} delivery: no secret is configured for this source", NAME);
      return false;
    }
    final var presented = delivery.header(TOKEN_HEADER);
    if (presented == null) {
      log.debug("Refusing a {} delivery: no {} header", NAME, TOKEN_HEADER);
      return false;
    }
    // Compared as bytes, not as strings. Two tokens of different lengths still differ here in
    // constant time for their common prefix; the length itself leaks, and cannot not leak, since a
    // header of a given length was sent over the wire either way.
    return MessageDigest.isEqual(
        secret.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public Optional<Observation> observation(final WebhookDelivery delivery) {
    final var body = delivery.bodyAsText();
    final JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (final JacksonException e) {
      log.warn("Could not parse an authentic {} payload", NAME, e);
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

    // object_kind is preferred over the header because it is the machine-readable one: the header
    // reads "Merge Request Hook" while the body says "merge_request", and a deployment writing a
    // policy wants the latter. The header is the fallback, slugged into the same shape so that the
    // two spellings cannot both end up configured for one thing.
    final var kind = kind(root, delivery.header(EVENT_HEADER));
    if (kind == null) {
      log.debug("Ignoring a {} delivery that names no kind of event", NAME);
      return Optional.empty();
    }

    final var project = projectPath(root);
    final var iid = iid(root);

    return Optional.of(
        Observation.builder()
            .source(NAME)
            .deliveryId(deliveryId(delivery))
            .kind(kind)
            .correlationKey(correlationKey(project, iid, kind))
            .title(title(project, iid, kind))
            .summary(summary(root, kind, project, iid))
            // As authenticated as anything else GitLab sends, which is to say by a static token
            // rather than by a signature — see the note on this class about what that is worth.
            .actor(Actor.authenticated(actor(root)))
            .payloadJson(body)
            // Left to default to now, as with GitHub: the payload's created_at belongs to the issue
            // or merge request, not to this event about it.
            .build());
  }

  /**
   * GitLab's own event UUID, which is repeated across its retries of one event and is what makes a
   * retry cost nothing. Where it is absent the identity falls back to the body's hash inside a
   * one-minute bucket, for the reason spelled out on {@code GrafanaWebhookSource#deliveryId}: a
   * retry arrives within seconds and must collapse, while the same payload again minutes later is
   * something happening twice and must not.
   */
  private String deliveryId(final WebhookDelivery delivery) {
    final var uuid = delivery.header(DELIVERY_HEADER);
    if (uuid != null && !uuid.isBlank()) {
      return uuid;
    }
    log.debug(
        "A {} delivery carried no {}; deriving an identity from the body", NAME, DELIVERY_HEADER);
    final var bucket = Instant.now().getEpochSecond() / DELIVERY_BUCKET_SECONDS;
    return NAME + ":body:" + sha256Hex(delivery.body()) + ":" + bucket;
  }

  private String kind(final JsonNode root, final String header) {
    final var objectKind = text(root, "object_kind");
    final var base = objectKind != null ? objectKind : slug(header);
    if (base == null) {
      return null;
    }
    // The action lives under object_attributes for issues and merge requests, and at the top level
    // for a pipeline or a release. Both are refinements of the same thing — "merge_request.open"
    // rather than "merge_request" — which is the granularity a policy is written at.
    final var action =
        firstText(text(root.path("object_attributes"), "action"), text(root, "action"));
    return action == null ? base : base + "." + action;
  }

  /**
   * The project's path with namespace, which is stable across a rename of the project's display
   * name and is what a human recognises. GitLab puts it in different places depending on the hook:
   * {@code project} on most, {@code repository} on the older push payloads.
   */
  private String projectPath(final JsonNode root) {
    return firstText(
        text(root.path("project"), "path_with_namespace"),
        text(root.path("project"), "name"),
        text(root.path("repository"), "name"));
  }

  /**
   * The issue's or merge request's iid — the number a human sees and quotes, per project — and not
   * the global {@code id}. Two projects both have a {@code #1}, which is why the project path is
   * part of the key rather than the iid standing alone.
   */
  private Integer iid(final JsonNode root) {
    final var attributes = root.path("object_attributes").path("iid");
    if (attributes.isIntegralNumber()) {
      return attributes.asInt();
    }
    // Note and pipeline payloads carry the thing they are about beside the note itself, so a
    // comment on a merge request correlates with the merge request rather than starting a
    // situation of its own.
    for (final var field : List.of("merge_request", "issue")) {
      final var value = root.path(field).path("iid");
      if (value.isIntegralNumber()) {
        return value.asInt();
      }
    }
    return null;
  }

  private String correlationKey(final String project, final Integer iid, final String kind) {
    if (project == null || project.isBlank()) {
      // Group- and instance-level hooks name no project. Correlating them by kind keeps them apart
      // from each other without pretending to know a subject.
      return NAME + ":" + kind;
    }
    if (iid != null) {
      return NAME + ":" + project + "!" + iid;
    }
    return NAME + ":" + project + ":" + kind;
  }

  private String title(final String project, final Integer iid, final String kind) {
    if (project == null || project.isBlank()) {
      return "GitLab " + kind;
    }
    if (iid != null) {
      return project + "!" + iid;
    }
    return project + " " + kind;
  }

  private String summary(
      final JsonNode root, final String kind, final String project, final Integer iid) {
    final var line = new StringBuilder(kind);
    if (project != null && !project.isBlank()) {
      line.append(" in ").append(project);
      if (iid != null) {
        line.append('!').append(iid);
      }
    }
    final var headline =
        firstText(
            text(root.path("object_attributes"), "title"),
            text(root.path("object_attributes"), "status"),
            text(root.path("merge_request"), "title"),
            text(root.path("issue"), "title"));
    if (headline != null) {
      line.append(": ").append(headline);
    }
    final var actor = actor(root);
    if (actor != null) {
      line.append(" (by ").append(actor).append(')');
    }
    return line.toString();
  }

  /**
   * Who GitLab says caused the event, for {@link Observation#actor()}.
   *
   * <p>Two spellings because GitLab has two: the object-oriented hooks nest it under {@code user},
   * while push and tag hooks put {@code user_username} at the top level.
   */
  private static String actor(final JsonNode root) {
    return firstText(text(root.path("user"), "username"), text(root, "user_username"));
  }

  /** {@code "Merge Request Hook"} as {@code "merge_request"}, so header and body agree. */
  private static String slug(final String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    final var slug =
        header
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+hook$", "")
            .trim()
            .replaceAll("[^a-z0-9]+", "_");
    return slug.isBlank() ? null : slug;
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
   * Text only where the payload really holds text, so structure cannot arrive where a name is due.
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
