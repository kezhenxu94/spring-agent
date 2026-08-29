package me.kezhenxu94.springagent.integration.grafana;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
 * <p>One observation per element of {@code alerts}, because a batch is not a subject: Grafana
 * groups by whatever the notification policy says, so one delivery can carry a disk filling up on
 * one host and a queue backing up on another, and folding them into a single observation would ask
 * the agent for one opinion about two unrelated things. Correlating each alert on its own
 * fingerprint is also what makes the batching invisible — the same alert arriving alone, then in a
 * batch of thirty, lands on the same situation either way.
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
  public List<Observation> observations(final WebhookDelivery delivery) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(delivery.bodyAsText());
    } catch (final JacksonException e) {
      log.warn("Could not parse an authentic {} payload", NAME, e);
      return List.of();
    }
    final var alerts = root.path("alerts");
    if (!alerts.isArray() || alerts.isEmpty()) {
      // Grafana's test button and its "no data" plumbing both send a body with no alerts in it.
      // Nothing happened, so nothing is recorded.
      log.debug("Ignoring a {} delivery that carries no alerts", NAME);
      return List.of();
    }
    final var bodyHash = sha256Hex(delivery.body());
    final var bucket = clock.instant().getEpochSecond() / DELIVERY_BUCKET_SECONDS;
    final var groupStatus = text(root, "status");

    final var observations = new ArrayList<Observation>(alerts.size());
    for (var index = 0; index < alerts.size(); index++) {
      final var alert = alerts.get(index);
      if (!alert.isObject()) {
        log.debug("Ignoring element {} of a {} batch: not an object", index, NAME);
        continue;
      }
      final var labels = labels(alert);
      final var status = firstText(text(alert, "status"), groupStatus, "firing");
      final var name = firstText(labels.get("alertname"), labels.get("rulename"), "alert");
      observations.add(
          Observation.builder()
              .source(NAME)
              .deliveryId(deliveryId(bodyHash, bucket, index))
              .kind("alert." + status)
              .correlationKey(correlationKey(alert, labels))
              .title(title(name, labels))
              .summary(summary(alert, labels, name, status))
              // The one alert rather than the whole batch: the batch is an artefact of Grafana's
              // grouping, and thirty alerts' worth of JSON stored against each of thirty
              // observations is thirty times the same evidence.
              .payloadJson(MAPPER.writeValueAsString(alert))
              // observedAt defaults to now, and startsAt is deliberately not used for it. startsAt
              // is when the condition began, so on a repeat notification of something that has been
              // firing for an hour it is an hour old; recorded as the observation's time, that
              // notification would arrive already older than the quiet period that closes a
              // situation, and a persistently firing alert would resolve itself on arrival. When it
              // started is evidence, and is in the summary.
              .build());
    }
    return List.copyOf(observations);
  }

  /**
   * The one genuinely interesting decision here: Grafana sends no delivery identity of any kind, so
   * one has to be minted, and what it is made of decides what counts as a retry.
   *
   * <p>The hash of the body alone would be wrong. Grafana re-notifies about an alert that is still
   * firing every {@code repeat_interval}, and that body is byte-for-byte the one sent before — so a
   * pure content hash would silently discard every re-notification, and "this has been firing for
   * an hour" is the most useful thing the source ever says. Time alone would be wrong the other
   * way: a retry after a failed delivery would be counted as a second alert.
   *
   * <p>So both, at a granularity that separates the two: content plus the minute it arrived in. A
   * retry follows a failure within seconds and lands in the same bucket, while a re-notification
   * comes minutes or hours later and is admitted as news. The cost is a retry that straddles a
   * bucket boundary being counted twice, which shows up as one duplicated piece of evidence inside
   * a situation that would have existed anyway — much cheaper than the alternative failure, which
   * is silence about an alert that never stopped.
   *
   * <p>The index keeps the alerts of one batch apart from each other; without it a delivery of
   * thirty would collapse into one observation.
   */
  private String deliveryId(final String bodyHash, final long bucket, final int index) {
    return NAME + ":" + bodyHash + ":" + bucket + ":" + index;
  }

  /**
   * Grafana's fingerprint is a hash of the alert's label set, computed by the alertmanager, and is
   * therefore exactly the right correlation key: the same rule firing about the same instance
   * carries the same fingerprint from the first notification to the resolution, so firing and
   * resolved land in one situation.
   *
   * <p>Where it is missing the labels are hashed here instead. Sorted, because a JSON object has no
   * order to rely on and two deliveries of the same alert would otherwise correlate differently.
   */
  private String correlationKey(final JsonNode alert, final TreeMap<String, String> labels) {
    final var fingerprint = text(alert, "fingerprint");
    if (fingerprint != null) {
      return NAME + ":" + fingerprint;
    }
    if (labels.isEmpty()) {
      // Nothing distinguishes this from any other unlabelled alert, and saying so is better than
      // inventing a key per delivery: they collapse into one situation about a source sending
      // alerts nobody can identify.
      log.debug("A {} alert carried neither a fingerprint nor labels", NAME);
      return NAME + ":unidentified";
    }
    final var canonical = new StringBuilder();
    labels.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
    return NAME + ":labels:" + sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The alert's labels as text, sorted, skipping anything that is not a string. A label whose value
   * arrives as an object or a number is not a label as far as correlation is concerned, and
   * coercing it would let the payload's author change a key by changing a type.
   */
  private TreeMap<String, String> labels(final JsonNode alert) {
    final var labels = new TreeMap<String, String>();
    final var node = alert.path("labels");
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

  private String title(final String name, final TreeMap<String, String> labels) {
    final var instance = firstText(labels.get("instance"), labels.get("service"));
    return instance == null ? name : name + " on " + instance;
  }

  private String summary(
      final JsonNode alert,
      final TreeMap<String, String> labels,
      final String name,
      final String status) {
    final var line = new StringBuilder(name).append(' ').append(status);
    final var instance = firstText(labels.get("instance"), labels.get("service"));
    if (instance != null) {
      line.append(" on ").append(instance);
    }
    final var severity = labels.get("severity");
    if (severity != null) {
      line.append(" [").append(severity).append(']');
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
