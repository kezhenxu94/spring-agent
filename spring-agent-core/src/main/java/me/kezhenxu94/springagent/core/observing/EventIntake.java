package me.kezhenxu94.springagent.core.observing;

import java.util.Optional;

/**
 * Where a surface reports something it saw that nobody asked the agent about — the contract core
 * depends on, implemented by {@code spring-agent-events}.
 *
 * <p>Core owns nothing behind this. There is no implementation here, exactly as there is none of
 * {@link me.kezhenxu94.springagent.core.knowledge.KnowledgeBase}, so a deployment without the
 * module simply observes nothing: a surface reaches the funnel through an {@code
 * ObjectProvider<EventIntake>} and does nothing where it is absent. That is a supported
 * configuration, not a degraded one.
 *
 * <p>Declaring it here rather than in the module is what keeps the compile dependency pointing the
 * right way. A transport — the Feishu integration, a webhook receiver, a poller — depends only on
 * core to report what it saw, and the module implementing this never depends on a transport. Adding
 * a source is therefore a matter of calling this from wherever the events already arrive, which is
 * how group chat messages join without an HTTP endpoint existing anywhere near them.
 *
 * <p>Implementations must be cheap and must not block on the model. A transport calls this from
 * whatever thread its events arrive on — a Feishu websocket dispatcher, a request thread — and
 * deciding whether to reason about an observation is the job of whatever wakes the agent later, not
 * of the call that records one.
 */
public interface EventIntake {

  /**
   * Records {@code observation} and says which situation it joined.
   *
   * <p>Never throws for an observation that was merely uninteresting: a duplicate delivery, a
   * source a deployment has not configured, or a chat nobody asked to be watched all come back
   * empty. A caller reports what it saw and is not asked to know the policy.
   *
   * @return the id of the situation this observation joined, or empty where it was dropped
   */
  Optional<String> observe(Observation observation);
}
