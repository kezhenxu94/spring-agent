package me.kezhenxu94.springagent.events.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.events.source.WebhookDelivery;
import me.kezhenxu94.springagent.events.source.WebhookSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one HTTP endpoint this module adds: where the systems a deployment watches post what
 * happened.
 *
 * <p>It knows none of them. Each arrives as a {@link WebhookSource} bean from its own {@code
 * spring-agent-integration-*} module and is selected by the last segment of the path, so adding a
 * system is adding a module and never a line here.
 *
 * <p>Unauthenticated as far as Spring Security is concerned — the path has to be permitted in the
 * application's own filter chain, since the chain there ends {@code anyRequest().hasRole("SEAMAN")}
 * and a webhook has no session to log in with. Authentication is per source instead, and is the
 * {@link WebhookSource}'s job: a signature over the body for GitHub, a shared token for the other
 * two. That is the only thing standing between this and the internet, so a source with no secret
 * configured refuses everything rather than accepting everything.
 *
 * <p>The body is taken as {@code byte[]} and stays bytes until a source has verified it. A
 * signature is over what was actually sent; binding to a type and re-serialising changes key order,
 * escaping and whitespace, and the signature then fails for reasons indistinguishable from a wrong
 * secret.
 *
 * <p>Nothing here calls a model, and nothing here waits for one. The request records what arrived
 * and returns; whether any of it is worth an opinion is settled later, by the sweeper, after the
 * debounce. That is what keeps a sender's timeout out of the agent's business — and what stops a
 * sender from retrying a delivery the agent is still thinking about.
 */
@Slf4j
@RestController
public class WebhookController {

  static final String PATH = "/events/webhooks/{source}";

  private final Map<String, WebhookSource> sources;
  private final EventsProperties properties;
  private final EventIntakes intakes;

  public WebhookController(
      final List<WebhookSource> sources,
      final EventsProperties properties,
      final EventIntakes intakes) {
    this.sources =
        sources.stream()
            .collect(
                Collectors.toMap(
                    WebhookSource::name,
                    Function.identity(),
                    (first, second) -> {
                      throw new IllegalStateException(
                          "Two webhook sources both call themselves " + first.name());
                    }));
    this.properties = properties;
    this.intakes = intakes;
    log.info(
        "Receiving webhooks at /events/webhooks/{{}}", String.join("|", this.sources.keySet()));
  }

  @PostMapping(PATH)
  public ResponseEntity<Void> receive(
      @PathVariable final String source,
      @RequestHeader final Map<String, String> headers,
      @RequestBody(required = false) final byte[] body) {

    final var known = sources.get(source);
    // Deliberately the same answer for a source that does not exist and one nobody configured: a
    // caller learning which of the two it is learns what this deployment watches.
    final var policy = properties.policyFor(source).orElse(null);
    if (known == null || policy == null) {
      log.debug("Refusing a delivery for unknown or unconfigured source {}", source);
      return ResponseEntity.notFound().build();
    }

    final var payload = body == null ? new byte[0] : body;
    if (payload.length > properties.maxBodySize().toBytes()) {
      log.warn(
          "Refusing a {} delivery of {} bytes, over the {} limit",
          source,
          payload.length,
          properties.maxBodySize());
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }

    final var delivery = new WebhookDelivery(new LinkedHashMap<>(headers), payload);
    if (!verified(known, delivery, policy)) {
      log.warn("Refusing a {} delivery: it did not authenticate", source);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // Failure of any one consumer is EventIntakes' business, not this method's.
    observation(known, delivery).ifPresent(intakes::observe);
    // No content, and nothing about what we made of it. A sender is being told the delivery
    // arrived,
    // which is all it can act on; what the agent decides happens minutes later and is not a reply.
    return ResponseEntity.noContent().build();
  }

  /**
   * Verification, with the source's own failure treated as a refusal.
   *
   * <p>{@code verify} is documented as never throwing, and every implementation here is written
   * that way — but this is the method reached by unauthenticated traffic, so the guard is here as
   * well as there. A source that throws on a malformed signature would otherwise answer 500 and say
   * in the log what it choked on, which is a more useful reply than a forger deserves.
   */
  private boolean verified(
      final WebhookSource source,
      final WebhookDelivery delivery,
      final EventsProperties.Policy policy) {
    try {
      return source.verify(delivery, policy.secret());
    } catch (RuntimeException e) {
      log.error("The {} source threw while verifying a delivery; refusing it", source.name(), e);
      return false;
    }
  }

  private Optional<Observation> observation(
      final WebhookSource source, final WebhookDelivery delivery) {
    try {
      return source.observation(delivery);
    } catch (RuntimeException e) {
      // Authentic but unreadable. Worth a loud log, since it means either a payload shape the
      // source does not know or a bug in reading one, and both are ours to fix rather than the
      // sender's.
      log.error("Could not read a {} delivery that had authenticated", source.name(), e);
      return Optional.empty();
    }
  }
}
