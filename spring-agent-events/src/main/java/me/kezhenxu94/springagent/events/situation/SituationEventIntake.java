package me.kezhenxu94.springagent.events.situation;

import com.google.common.util.concurrent.Striped;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;
import me.kezhenxu94.springagent.core.observing.EventIntake;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.stereotype.Component;

/**
 * The cheap half of the feature: records what a surface saw, files it under the situation it
 * belongs to, and works out when that situation will be worth an opinion. Nothing here reasons, and
 * nothing here calls a model.
 *
 * <p>That separation is the whole design. A surface reports every alert and every message, on
 * whatever thread they arrive on, and this stays arithmetic — a claim, two writes and a comparison
 * of timestamps. Whether to spend a model call on the result is a decision {@link SituationSweeper}
 * makes later, once things have stopped moving.
 *
 * <p>Deduplication lives here rather than in each transport, so there is one namespace of claims
 * and one definition of what a retry is. A transport's only obligation is that {@link
 * Observation#deliveryId()} is stable across a redelivery and different for a genuine repeat.
 */
@Slf4j
@Component
public class SituationEventIntake implements EventIntake {

  private final EventsProperties properties;
  private final SituationRepo situations;
  private final ObservedEventRepo events;
  private final ProcessedMessageRepo processedMessages;
  private final Clock clock;

  /**
   * Serialises the read-then-create of a situation, per correlation key.
   *
   * <p>Two observations for a key with no open situation would otherwise both find none and both
   * create one, and the second situation would earn its own triage run — which for a chat means the
   * agent chiming in twice about one exchange. Striped rather than a lock per key so the map does
   * not grow with every key ever seen.
   *
   * <p>In-process only, and honestly so: two replicas racing on a key that is new to both can still
   * produce two situations. Making that impossible needs a uniqueness constraint no backend here
   * can offer — Redis secondary indexes cannot express one at all. What keeps it rare in practice
   * is that a source's second observation almost always arrives after the first has been recorded,
   * and what keeps it cheap when it does happen is the cooldown.
   */
  private final Striped<java.util.concurrent.locks.Lock> locks = Striped.lock(64);

  public SituationEventIntake(
      final EventsProperties properties,
      final SituationRepo situations,
      final ObservedEventRepo events,
      final ProcessedMessageRepo processedMessages,
      final Clock clock) {
    this.properties = properties;
    this.situations = situations;
    this.events = events;
    this.processedMessages = processedMessages;
    this.clock = clock;
  }

  @Override
  public void observe(final Observation observation) {
    final var policy = properties.policyFor(observation.source()).orElse(null);
    if (policy == null) {
      // Not an error. A deployment configures the sources it wants, and a surface reports what it
      // saw without being asked to know which those are.
      log.debug("Ignoring observation from {}: the source is not configured", observation.source());
      return;
    }

    final var claimKey = claimKey(observation);
    if (!processedMessages.claim(claimKey)) {
      log.debug("Ignoring observation {}: it has already been recorded", claimKey);
      return;
    }

    try {
      final var lock = locks.get(observation.correlationKey());
      lock.lock();
      try {
        record(observation, policy);
      } finally {
        lock.unlock();
      }
    } catch (RuntimeException e) {
      // The claim goes back, so that a redelivery is recorded rather than passed over for good —
      // the same reasoning as the release in the Feishu message handler. Re-recording an
      // observation is
      // harmless: the event row is keyed by delivery id and so overwrites itself.
      processedMessages.release(claimKey);
      throw e;
    }
  }

  private Situation record(final Observation observation, final EventsProperties.Policy policy) {
    final var now = clock.instant();
    final var situation = openSituationFor(observation, policy, now);
    final var eventCount = situation.eventCount() == null ? 0 : situation.eventCount();
    final var recordedCount = eventCount + 1;

    // Past the cap the observation is counted and not kept. At that scale the count is what anybody
    // reasons about — "nine hundred of these" — and the nine hundredth payload is not evidence
    // anyone reads. It also bounds what findBySituationId has to return, which is what lets that
    // query be unsorted and unpaged.
    if (recordedCount <= properties.maxEventsPerSituation()) {
      events.save(
          ObservedEvent.builder()
              .id(observation.deliveryId())
              .situationId(situation.id())
              .source(observation.source())
              .kind(observation.kind())
              .summary(truncate(observation.summary(), 1024))
              .payloadJson(truncate(observation.payloadJson(), 131072))
              .observedAt(observation.observedAt())
              .build());
    }

    final var awaitingSince = situation.awaitingSince() == null ? now : situation.awaitingSince();
    return situations.save(
        situation.toBuilder()
            .eventCount(recordedCount)
            .lastEventAt(now)
            .awaitingSince(awaitingSince)
            // A run in flight keeps the phase it has. Moving it back to AWAITING_EVALUATION here
            // would let the sweeper start a second run for the same situation while the first is
            // still going; the lifecycle listener puts it back when the run ends, having seen that
            // observations arrived meanwhile.
            .phase(
                situation.phase() == Situation.Phase.INVESTIGATING
                    ? Situation.Phase.INVESTIGATING
                    : Situation.Phase.AWAITING_EVALUATION)
            .evaluateAfter(evaluateAfter(situation, policy, awaitingSince, now))
            .build());
  }

  /**
   * When this situation should next be looked at: a deadline every observation pushes further out,
   * between a ceiling and a floor.
   *
   * <p>The deadline is the debounce, and it is what turns a thousand alerts about one outage into
   * one run: while they keep arriving, the situation has not settled into anything worth an
   * opinion.
   *
   * <p>The ceiling is {@code max-debounce}, measured from {@code awaitingSince} rather than from
   * now, because a moving anchor would not cap anything — a source emitting steadily would defer
   * its evaluation for ever, which is precisely the case where somebody wants to be told.
   *
   * <p>The floor is the cooldown, and it is applied last so that it wins. It is what stops a busy
   * situation from being re-read every debounce, and for a group chat it is the difference between
   * an agent that occasionally helps and one everybody mutes.
   */
  private Instant evaluateAfter(
      final Situation situation,
      final EventsProperties.Policy policy,
      final Instant awaitingSince,
      final Instant now) {
    var due = now.plus(policy.debounce());
    final var ceiling = awaitingSince.plus(policy.maxDebounce());
    if (due.isAfter(ceiling)) {
      due = ceiling;
    }
    if (situation.lastEvaluatedAt() != null) {
      final var floor = situation.lastEvaluatedAt().plus(policy.cooldown());
      if (due.isBefore(floor)) {
        due = floor;
      }
    }
    return due;
  }

  private Situation openSituationFor(
      final Observation observation, final EventsProperties.Policy policy, final Instant now) {
    final var open =
        situations.findByCorrelationKeyAndStatus(
            observation.correlationKey(), Situation.Status.OPEN);
    if (!open.isEmpty()) {
      // Newest first, so that the residue of the cross-replica race described above converges on
      // one
      // situation rather than splitting observations between two.
      return open.stream()
          .max(Comparator.comparing(s -> s.lastEventAt() == null ? Instant.EPOCH : s.lastEventAt()))
          .orElseThrow();
    }

    // Where a run may talk: what the observation knows, or what the source was configured with.
    // A chat message knows its own chat; an alert knows nothing and has to be told.
    final var route = observation.route().orElse(policy.route());
    final var created =
        situations.save(
            Situation.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .source(observation.source())
                .correlationKey(observation.correlationKey())
                .title(truncate(title(observation), 512))
                .status(Situation.Status.OPEN)
                .phase(Situation.Phase.AWAITING_EVALUATION)
                .firstSeenAt(now)
                .awaitingSince(now)
                .generation(0)
                .eventCount(0)
                .ownerUserId(policy.ownerUserId())
                .chatId(route.chatId())
                .chatType(route.chatType())
                .groupId(route.groupId())
                .tenantId(route.tenantId())
                .build());
    log.info(
        "Opened situation {} for {} ({})",
        created.id(),
        observation.correlationKey(),
        created.title());
    return created;
  }

  private static String title(final Observation observation) {
    if (observation.title() != null && !observation.title().isBlank()) {
      return observation.title();
    }
    // Something rather than nothing: the correlation key is at least stable and says what this is
    // about, and a source that gave no title is a source worth noticing in the logs.
    return observation.correlationKey();
  }

  /**
   * Namespaced twice over.
   *
   * <p>By source, so that two systems whose delivery ids happen to collide — a bare numeric id from
   * one, the same number from another — cannot silence each other. And by this intake, because
   * every intake in the application is given every observation and "already seen" means something
   * different to each of them: a claim key shared with somebody else's intake would let whichever
   * ran first silence the rest.
   */
  private static String claimKey(final Observation observation) {
    return "situations:observed:" + observation.source() + ":" + observation.deliveryId();
  }

  private static String truncate(final String value, final int limit) {
    if (value == null || value.length() <= limit) {
      return value;
    }
    return value.substring(0, limit);
  }
}
