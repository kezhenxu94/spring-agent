package me.kezhenxu94.springagent.events.source;

import java.util.Optional;
import me.kezhenxu94.springagent.core.observing.Observation;

/**
 * One kind of system that pushes events at us over HTTP: how to tell a genuine delivery from a
 * forged one, and how to read it as {@link Observation}s.
 *
 * <p>Not the general way a source reaches this module — {@link
 * me.kezhenxu94.springagent.core.observing.EventIntake} is, and it knows nothing about HTTP. This
 * exists because the things that arrive over HTTP share three problems no other transport has: a
 * path segment has to select between them, a signature has to be checked over the raw body, and a
 * delivery identity has to be dug out of vendor-specific headers. A chat message or a poller's row
 * has none of those and does not implement this.
 *
 * <p>Implementations are stateless and take their secret as an argument rather than reading
 * configuration, which is what makes each one testable with a byte array and a string.
 *
 * <p>They live in their own {@code spring-agent-integration-*} modules, one per system, so that a
 * deployment takes only the ones it has and this module knows about none of them. What a source is
 * called here is also the key its settings live under and the last segment of its path, which is
 * the whole of how the three are tied together.
 */
public interface WebhookSource {

  /**
   * The name this source is known by, in both places at once: the last segment of its webhook path,
   * and the key its settings live under in {@code app.events.sources}. One name for both, so a
   * deployment cannot configure a policy for a path that does not exist.
   */
  String name();

  /**
   * Whether this delivery really came from the system it claims to.
   *
   * <p>Called before anything else looks at the body, and must not throw for a malformed or hostile
   * request — a missing header, a signature of the wrong length, unparseable text and an empty body
   * are all just false. The comparison itself has to be {@code MessageDigest.isEqual} and not
   * {@code String.equals}: the latter returns as soon as two bytes differ, which tells a patient
   * caller how much of a guess was right.
   *
   * @param secret what the deployment configured for this source, or null where it configured none.
   *     A source with no secret must refuse everything rather than accept everything.
   */
  boolean verify(WebhookDelivery delivery, String secret);

  /**
   * What this delivery says, as one observation.
   *
   * <p>One delivery is one observation, and the type says so rather than the documentation. A
   * source that took a batch apart would be second-guessing the sender's own grouping — Grafana
   * decides what belongs together from the contact point's {@code group_by} and posts the group,
   * and splitting it asks the agent for an opinion about each alert in a batch it was told is one
   * thing. It also multiplies the evidence: thirty alerts recorded separately store the delivery
   * thirty times.
   *
   * <p>Empty is a normal answer for a delivery that says nothing worth recording — a ping, a test
   * button, a body with no events in it — and is not an error.
   *
   * <p>Called only after {@link #verify} has passed, so this may assume the payload is authentic —
   * but never that it is well-formed or truthful, since its text was written by whoever caused the
   * event.
   */
  Optional<Observation> observation(WebhookDelivery delivery);
}
