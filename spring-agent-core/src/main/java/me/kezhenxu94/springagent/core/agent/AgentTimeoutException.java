package me.kezhenxu94.springagent.core.agent;

import java.time.Duration;

/**
 * Thrown by {@link SpringAgent#fireAndAwait(AgentRequest, Duration)} when the run named by {@code
 * requestId} has not reached {@link AgentResponseListener#onFinished} within {@code timeout}.
 *
 * <p>The run itself is not stopped by this — nothing here calls {@link SpringAgent#cancel(String)}
 * — so a caller that wants the run actually ended, rather than merely stopped waiting on it, does
 * that itself.
 */
public class AgentTimeoutException extends RuntimeException {

  public AgentTimeoutException(final String requestId, final Duration timeout) {
    super("Agent request " + requestId + " did not finish within " + timeout);
  }
}
