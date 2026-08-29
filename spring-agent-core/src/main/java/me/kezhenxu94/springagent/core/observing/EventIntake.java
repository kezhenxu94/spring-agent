package me.kezhenxu94.springagent.core.observing;

/**
 * Somewhere an {@link Observation} goes — the contract core depends on and ships no implementation
 * of, as it ships none of {@link me.kezhenxu94.springagent.core.knowledge.KnowledgeBase}.
 *
 * <p><b>As many as an application wants.</b> Every implementation in the context is given every
 * observation, so consuming the same events twice for different reasons is the ordinary case rather
 * than something to work around: {@code spring-agent-events} correlates them into situations and
 * wakes the agent for the ones worth an opinion, while an application that only wants a line in a
 * chat whenever anything happens writes a bean of its own and gets one, without either of them
 * knowing about the other. {@link EventIntakes} is what fans out; nothing has to be registered
 * anywhere beyond being a bean.
 *
 * <p>Which is why this returns nothing. It once returned the situation an observation joined — a
 * concept belonging to one implementation, meaningless to the others, and unanswerable once there
 * is more than one of them. What an intake made of an observation is its own business and is
 * reported wherever it keeps its records.
 *
 * <p>Implementations are independent and must behave as if they run alone. In particular an intake
 * that deduplicates through {@link
 * me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo#claim} has to namespace its keys to
 * itself: "already seen" means something different to each of them, and sharing a key would let
 * whichever ran first silence the rest.
 *
 * <p>Declaring this in core rather than in the module that implements it keeps the compile
 * dependency pointing the right way. A transport — the Feishu integration, a webhook receiver, a
 * poller — depends only on core to report what it saw, and nothing consuming observations ever
 * depends on a transport. Adding a source is a matter of calling {@link EventIntakes} from wherever
 * the events already arrive, which is how group chat messages join without an HTTP endpoint
 * existing anywhere near them.
 *
 * <p>Implementations must be cheap and must not block on the model. A transport calls this from
 * whatever thread its events arrive on — a Feishu websocket dispatcher, a request thread — and
 * deciding whether to reason about an observation is the job of whatever wakes the agent later, not
 * of the call that records one.
 */
@FunctionalInterface
public interface EventIntake {

  /**
   * Takes {@code observation}, or ignores it.
   *
   * <p>Ignoring is ordinary and needs no explanation: a duplicate delivery, a source this intake
   * was not configured for, a kind of event it does not care about. A transport reports what it saw
   * and is not asked to know any intake's policy.
   *
   * <p>Should not throw. {@link EventIntakes} catches and logs, so one intake cannot take the
   * others down with it, but an intake that throws to signal "not mine" is using an exception for
   * control flow and will only fill the log.
   */
  void observe(Observation observation);
}
