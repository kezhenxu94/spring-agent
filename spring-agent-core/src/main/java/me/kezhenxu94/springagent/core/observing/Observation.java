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
 * @param actor who caused it, as an identity the transport can <em>vouch for</em>, for the one
 *     purpose of deciding whether to admit this observation at all. Blank where the transport
 *     cannot authenticate one, which is the honest answer for an alerting webhook and for anything
 *     machine-generated.
 *     <p>Authenticated is the whole of the word. GitHub names the actor inside a body an HMAC has
 *     already covered, so it is one; an email {@code From:} header is a string anybody can type, so
 *     it is not, and a source with no better evidence than that must report nothing here rather
 *     than something plausible. Reporting a name lifted from an unauthenticated part of a payload
 *     turns the allow-list this feeds into a bypass, since the attacker writes both sides of the
 *     comparison.
 *     <p>Read by {@code TrustedActors} against {@code app.events.sources.<source>.trusted-actors}
 *     and by nothing else. It is never routing, never the identity a run assumes, and never
 *     rendered into a prompt.
 *     <p>Which is why who caused the event is <em>also</em> still in {@link #summary}, and why that
 *     is not a duplication to tidy away. The two say different things: this one is a fact we
 *     established and act on, that one is the display name whoever caused the event chose for
 *     themselves — evidence like the rest of the payload, shown to the model as such. The identity
 *     a triage run assumes is neither of them, for the reasons in {@code SituationTriageScenario}.
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
    String actor) {

  public Observation {
    source = requireText(source, "source");
    deliveryId = requireText(deliveryId, "deliveryId");
    correlationKey = requireText(correlationKey, "correlationKey");
    // The three above are the ones something breaks without: without a source there is no policy to
    // apply, without a delivery id every redelivery is counted again, and without a correlation key
    // every observation becomes a situation of its own and the debounce protects nothing.
    observedAt = observedAt == null ? Instant.now() : observedAt;
    route = route == null ? Route.NONE : route;
    // Not defaulted and not required: absent is a real answer, and it means the opposite of the
    // empty string meaning "nobody". It means this transport cannot tell us, which is what
    // TrustedActors has to be able to distinguish from an actor it was given and did not like.
  }

  private static String requireText(final String value, final String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
