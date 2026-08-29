package me.kezhenxu94.springagent.integration.grafana;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grafana's unified alerting webhook contact point, which posts a batch of alerts rather than one
 * event.
 *
 * <p>One delivery is one observation, batch and all. Grafana has already decided what belongs
 * together — the contact point's {@code group_by} is exactly that decision, and {@code groupKey}
 * names the group it produced — so taking the batch apart would be second-guessing the sender about
 * its own alerts, and would ask the agent for a separate opinion on each of thirty things it was
 * told are one thing.
 *
 * <p>The consequence worth knowing: a contact point grouped loosely, {@code group_by: []} most of
 * all, puts unrelated alerts in one delivery and therefore in one situation. That is Grafana's
 * grouping showing through rather than something to correct here, and the fix for it is the
 * notification policy.
 *
 * <p>Authentication is whatever the contact point was configured with, since Grafana offers a
 * bearer token or HTTP basic and no signature at all. Both carry the same shared secret, so both
 * are accepted, and — as with GitLab — nothing binds the credential to the body: a copy of one
 * request is a licence to send any other. TLS is what that rests on.
 */
@Slf4j
@RequiredArgsConstructor
public class GrafanaWebhookSource implements WebhookSource {

  private static final String NAME = "grafana";

  /**
   * Read rather than {@code Instant.now()}, because the bucket below is the one piece of behaviour
   * in this class that depends on what time it is — and the rest of this module already reads its
   * time from here for the same reason. A test that could not move the clock had to loop until two
   * calls landed in the same bucket to say anything about the bucket at all.
   */
  private final Clock clock;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "bearer ";
  private static final String BASIC_PREFIX = "basic ";

  /**
   * The width of the window inside which two identical bodies count as one delivery. A minute is
   * chosen against two numbers Grafana works to: its retry of a failed notification happens within
   * seconds, and its {@code repeat_interval} is measured in minutes at the very least — usually
   * hours.
   */
  private static final long DELIVERY_BUCKET_SECONDS = 60;

  /** Past this a correlation key is stored as a digest; the column it lands in is bounded. */
  private static final int MAX_KEY_LENGTH = 180;

  /** How many alerts of a batch the summary names before it says how many more there are. */
  private static final int SUMMARY_ALERTS = 5;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean verify(final WebhookDelivery delivery, final String secret) {
    if (secret == null || secret.isBlank()) {
      log.debug("Refusing a {} delivery: no secret is configured for this source", NAME);
      return false;
    }
    final var header = delivery.header(AUTHORIZATION_HEADER);
    if (header == null || header.isBlank()) {
      log.debug("Refusing a {} delivery: no {} header", NAME, AUTHORIZATION_HEADER);
      return false;
    }
    final var scheme = header.toLowerCase(Locale.ROOT);
    if (scheme.startsWith(BEARER_PREFIX)) {
      return constantTimeEquals(secret, header.substring(BEARER_PREFIX.length()).trim());
    }
    if (scheme.startsWith(BASIC_PREFIX)) {
      return basicPasswordMatches(secret, header.substring(BASIC_PREFIX.length()).trim());
    }
    log.debug("Refusing a {} delivery: unsupported authorization scheme", NAME);
    return false;
  }

  /**
   * The password half of a basic credential, and only that half. The username is deliberately not
   * checked: Grafana's contact point makes it a free-text field a deployment fills in with
   * anything, so requiring a particular value would reject genuine traffic for a reason nothing in
   * the configuration of this module explains, while checking it against nothing adds no security —
   * the password is the secret.
   */
  private boolean basicPasswordMatches(final String secret, final String encoded) {
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (final IllegalArgumentException e) {
      log.debug("Refusing a {} delivery: basic credentials are not base64", NAME);
      return false;
    }
    final var credentials = new String(decoded, StandardCharsets.UTF_8);
    final var separator = credentials.indexOf(':');
    if (separator < 0) {
      log.debug("Refusing a {} delivery: basic credentials carry no password", NAME);
      return false;
    }
    return constantTimeEquals(secret, credentials.substring(separator + 1));
  }

  @Override
  public Optional<Observation> observation(final WebhookDelivery delivery) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(delivery.bodyAsText());
    } catch (final JacksonException e) {
      log.warn("Could not parse an authentic {} payload", NAME, e);
      return Optional.empty();
    }
    final var alerts = root.path("alerts");
    if (!alerts.isArray() || alerts.isEmpty()) {
      // Grafana's test button and its "no data" plumbing both send a body with no alerts in it.
      // Nothing happened, so nothing is recorded.
      log.debug("Ignoring a {} delivery that carries no alerts", NAME);
      return Optional.empty();
    }
    final var status = firstText(text(root, "status"), "firing");
    return Optional.of(
        Observation.builder()
            .source(NAME)
            .deliveryId(deliveryId(delivery))
            .kind("alert." + status)
            .correlationKey(correlationKey(root))
            .title(title(root, alerts))
            .summary(summary(root, alerts, status))
            // The delivery as it arrived. One observation now stands for the whole batch, so the
            // batch is what the evidence has to be — and it is stored once rather than once per
            // alert, which is most of what splitting used to cost.
            .payloadJson(delivery.bodyAsText())
            // observedAt defaults to now, and startsAt is deliberately not used for it. startsAt is
            // when the condition began, so on a repeat notification of something that has been
            // firing for an hour it is an hour old; recorded as the observation's time, that
            // notification would arrive already older than the quiet period that closes a
            // situation, and a persistently firing alert would resolve itself on arrival. When it
            // started is evidence, and is in the summary.
            .build());
  }

  /**
   * What counts as the same delivery arriving twice.
   *
   * <p>Grafana sends no delivery id of its own, so one is made from the body and the minute it
   * arrived in. A minute is chosen against two numbers Grafana works to: it retries a failed
   * notification within seconds, so a retry lands in the same bucket and is recognised; and its
   * {@code repeat_interval} is measured in minutes at the least and usually hours, so a
   * re-notification of something still firing lands in a later bucket and is admitted as news —
   * which it is, since "still firing an hour later" is the thing somebody wants to know.
   *
   * <p>The cost is a retry that straddles a bucket boundary being counted twice, which shows up as
   * one duplicated observation inside a situation that would have existed anyway. Much cheaper than
   * the other failure, which is never hearing that an alert is still going.
   *
   * <p>No index any more. There was one when a delivery became an observation per alert; a delivery
   * is now one observation, so the body and the minute identify it completely.
   */
  private String deliveryId(final WebhookDelivery delivery) {
    final var bucket = clock.instant().getEpochSecond() / DELIVERY_BUCKET_SECONDS;
    return NAME + ":" + sha256Hex(delivery.body()) + ":" + bucket;
  }

  /**
   * The group this delivery is about, which is Grafana's own idea of it.
   *
   * <p>{@code groupKey} is what the notification policy grouped by, so the same group arriving
   * again — with more alerts in it, or fewer — lands on the same situation, which is the behaviour
   * that makes "this has been going on for twenty minutes" answerable. Falling back to the group
   * labels covers an older Grafana that sends none; falling back to a constant is for a payload
   * with nothing to group on at all, where collapsing into one situation about a source nobody can
   * identify beats inventing a situation per delivery.
   *
   * <p>Hashed once it grows long. A group key spells out every label it grouped on and has no
   * bound, while the column this lands in has one.
   */
  private String correlationKey(final JsonNode root) {
    final var groupKey = text(root, "groupKey");
    if (groupKey != null) {
      return NAME + ":group:" + shortened(groupKey);
    }
    final var groupLabels = labels(root.path("groupLabels"));
    if (!groupLabels.isEmpty()) {
      return NAME + ":group:" + shortened(canonical(groupLabels));
    }
    final var commonLabels = labels(root.path("commonLabels"));
    if (!commonLabels.isEmpty()) {
      return NAME + ":common:" + shortened(canonical(commonLabels));
    }
    log.debug("A {} delivery carried neither a group key nor labels to group on", NAME);
    return NAME + ":ungrouped";
  }

  /** As given where it is short enough to read in a log, and a digest of it where it is not. */
  private static String shortened(final String key) {
    return key.length() <= MAX_KEY_LENGTH ? key : sha256Hex(key.getBytes(StandardCharsets.UTF_8));
  }

  private static String canonical(final TreeMap<String, String> labels) {
    final var text = new StringBuilder();
    labels.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    return text.toString();
  }

  /**
   * A labels object as text, sorted, skipping anything that is not a string. A label whose value
   * arrives as an object or a number is not a label as far as correlation is concerned, and
   * coercing it would let the payload's author change a key by changing a type.
   *
   * <p>Takes the labels node rather than the thing carrying it, because the same shape appears
   * three times in a Grafana payload: on the group, on what the alerts have in common, and on each
   * alert.
   */
  private TreeMap<String, String> labels(final JsonNode node) {
    final var labels = new TreeMap<String, String>();
    if (!node.isObject()) {
      return labels;
    }
    node.propertyNames()
        .forEach(
            key -> {
              final var value = node.path(key);
              if (value.isString()) {
                labels.put(key, value.stringValue());
              }
            });
    return labels;
  }

  /** What the group is about, from what Grafana grouped on, and how much of it there is. */
  private String title(final JsonNode root, final JsonNode alerts) {
    final var groupLabels = labels(root.path("groupLabels"));
    final var commonLabels = labels(root.path("commonLabels"));
    final var name =
        firstText(
            groupLabels.get("alertname"),
            commonLabels.get("alertname"),
            groupLabels.get("rulename"),
            commonLabels.get("rulename"),
            text(root, "title"));
    final var where = firstText(commonLabels.get("instance"), commonLabels.get("service"));
    if (name == null) {
      return alerts.size() + " Grafana alerts";
    }
    final var title = where == null ? name : name + " on " + where;
    return alerts.size() == 1 ? title : title + " (" + alerts.size() + " alerts)";
  }

  /**
   * The batch in one line each, up to a point.
   *
   * <p>Capped because a group can hold hundreds and the summary is what goes into the brief a
   * triage run is given; the rest are one GetSituationEvents call away, in the payload. How many
   * were left out is said rather than implied, and so is Grafana having truncated the batch before
   * we saw it.
   */
  private String summary(final JsonNode root, final JsonNode alerts, final String status) {
    final var line = new StringBuilder();
    line.append(alerts.size()).append(alerts.size() == 1 ? " alert " : " alerts ").append(status);
    final var truncated = root.path("truncatedAlerts");
    if (truncated.isNumber() && truncated.intValue() > 0) {
      line.append(", ").append(truncated.intValue()).append(" more truncated by Grafana");
    }
    line.append(':');

    final var shown = Math.min(alerts.size(), SUMMARY_ALERTS);
    for (var index = 0; index < shown; index++) {
      final var alert = alerts.get(index);
      if (!alert.isObject()) {
        continue;
      }
      line.append("\n- ").append(describe(alert));
    }
    if (alerts.size() > shown) {
      line.append("\n- and ").append(alerts.size() - shown).append(" more");
    }
    return line.toString();
  }

  /** One alert of the batch, as much of it as is worth a line. */
  private String describe(final JsonNode alert) {
    final var labels = labels(alert.path("labels"));
    final var name = firstText(labels.get("alertname"), labels.get("rulename"), "alert");
    final var line = new StringBuilder(name);
    final var instance = firstText(labels.get("instance"), labels.get("service"));
    if (instance != null) {
      line.append(" on ").append(instance);
    }
    final var severity = labels.get("severity");
    if (severity != null) {
      line.append(" [").append(severity).append(']');
    }
    final var status = text(alert, "status");
    if (status != null) {
      line.append(' ').append(status);
    }
    final var annotations = alert.path("annotations");
    final var detail = firstText(text(annotations, "summary"), text(annotations, "description"));
    if (detail != null) {
      line.append(": ").append(detail);
    }
    final var startsAt = text(alert, "startsAt");
    if (startsAt != null) {
      line.append(" (since ").append(startsAt).append(')');
    }
    return line.toString();
  }

  private static boolean constantTimeEquals(final String secret, final String presented) {
    return MessageDigest.isEqual(
        secret.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  private static String firstText(final String... candidates) {
    for (final var candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

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
