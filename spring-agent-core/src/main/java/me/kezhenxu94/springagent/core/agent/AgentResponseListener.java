package me.kezhenxu94.springagent.core.agent;

import org.springframework.ai.chat.metadata.Usage;

/**
 * Callbacks for an agent run. There are two ways to get them, and they differ only in reach:
 *
 * <ul>
 *   <li>attached to an {@link AgentRequest}, for the run that request describes;
 *   <li>declared as a bean, for every run — which is how an integration takes part in runs it does
 *       not initiate. Such a bean is normally interested in {@link #onStart} alone, where it can
 *       attach per-run state to the run being assembled.
 * </ul>
 *
 * <p>Every callback is invoked on the thread driving the run, and a callback that throws is logged
 * and ignored rather than failing the run.
 */
public interface AgentResponseListener {

  /**
   * The run has been described but not yet assembled — the last point at which {@code registry} can
   * still contribute listeners, todo handlers or tool-context entries to it. Called on every
   * listener known at that point, which is where a bean listener decides whether this run concerns
   * it at all; a listener that {@code registry} itself attaches arrives too late to be called.
   */
  default void onStart(AgentRunRegistry setup) {}

  default void onSubscribe() {}

  default void onModel(String model) {}

  /** The response accumulated so far, not the latest delta. */
  default void onContent(String contentSoFar) {}

  default void onUsage(String model, Usage usage) {}

  default void onError(Throwable error) {}

  /** Invoked exactly once per run, whatever the {@link AgentOutcome}. */
  default void onFinished(AgentOutcome outcome) {}

  /** Returning {@code false} stops consuming the response, ending the run as cancelled. */
  default boolean shouldContinue() {
    return true;
  }
}
