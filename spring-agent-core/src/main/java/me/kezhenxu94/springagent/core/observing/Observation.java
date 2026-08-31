package me.kezhenxu94.springagent.core.observing;

import java.time.Instant;
import java.util.Objects;
import lombok.Builder;

/**
 * One thing a surface saw that nobody asked the agent about: an alert that fired, an issue somebody
 * opened, a message in a group chat the bot was not addressed in.
 *
 * <p>Deliberately not a persistence model and deliberately not a transport's own payload type. A
 * webhook body, a chat event and a poller's row have nothing in common but this, and giving every
 * transport the same small record to fill in is what keeps {@link EventIntake} from growing a
 * method per source.
 *
 * @param source who saw it, as a stable name a deployment configures policy under — {@code
 *     "github"}, {@code "grafana"}, {@code "feishu-chat"}. It selects the settings under {@code
 *     app.events.sources.<source>}, so renaming one silently moves it onto the defaults.
 * @param deliveryId the transport's own idempotency key, and the whole definition of what counts as
 *     a retry rather than as news. A transport that has no such key has to mint one that is stable
 *     for a redelivery and different for a genuine repeat — see the per-source notes on the webhook
 *     sources, where this is the one thing each of them has to decide.
 * @param kind what happened, in the source's vocabulary: {@code "alert.firing"}, {@code
 *     "issues.opened"}, {@code "chat.message"}. Reported to the model as-is and never interpreted
 *     here.
 * @param correlationKey what groups observations into one situation. Computed in code, never by the
 *     model: the point of the deterministic layer is that a thousand alerts about one database
 *     collapse without an inference costing anything.
 * @param title one line naming the situation this belongs to, used when it creates one
 * @param summary what this particular observation says, for the evidence list
 * @param payloadJson the raw thing as it arrived, kept so the agent can be shown detail the summary
 *     left out. Text written by whoever triggered the event, so treated as untrusted throughout.
 * @param observedAt when it happened, as the source reports it rather than when we heard; defaults
 *     to now for a transport with nothing better to say
 * @param route where a run about this may talk, where the observation itself knows — a chat
 *     observation does, a webhook does not and takes it from configuration. Null means it knows
 *     nowhere.
 * @param actor who caused it, and whether the transport could vouch for it being them — see {@link
 *     Actor}, which is where the difference between the two is kept and why a decision may only be
 *     made on one of them. Null where the event names nobody at all, which is the honest answer for
 *     an alerting webhook and for anything machine-generated.
 *     <p>Read for a decision by {@code TrustedActors}, against {@code
 *     app.events.sources.<source>.trusted-actors}, and by nothing else. Read as evidence by
 *     whatever intake an application wrote for reasons of its own.
 */
@Builder
public record Observation(
    String source,
    String deliveryId,
    String kind,
    String correlationKey,
    String title,
    String summary,
    String payloadJson,
    Instant observedAt,
    Route route,
    Actor actor) {

  public Observation {
    source = requireText(source, "source");
    deliveryId = requireText(deliveryId, "deliveryId");
    correlationKey = requireText(correlationKey, "correlationKey");
    // The three above are the ones something breaks without: without a source there is no policy to
    // apply, without a delivery id every redelivery is counted again, and without a correlation key
    // every observation becomes a situation of its own and the debounce protects nothing.
    observedAt = observedAt == null ? Instant.now() : observedAt;
    route = route == null ? Route.NONE : route;
    // actor is not defaulted and not required: absent is a real answer and means the event names
    // nobody, which TrustedActors has to be able to tell apart from an actor it was given and did
    // not like. An actor that is there but unverified is a third answer again, and is Actor's to
    // carry rather than this record's to encode in a null.
  }

  private static String requireText(final String value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
