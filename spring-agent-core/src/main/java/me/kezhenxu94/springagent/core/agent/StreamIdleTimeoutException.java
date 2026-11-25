package me.kezhenxu94.springagent.core.agent;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * A model's answer stopped arriving partway: nothing came for longer than a provider's stream idle
 * timeout, so the stream was abandoned rather than waited on for the whole request timeout.
 *
 * <p>A {@link SocketTimeoutException}, which is what it replaces, so that whatever already reads
 * one as a timeout still does — a triage run failing on it is retried as a failure that may pass.
 * Its own type so that {@code StreamStallRetryAdvisor} retries this and nothing else: a provider
 * module throws it where it knows the silence was a stream going idle, and a read timeout of any
 * other kind keeps meaning what it meant.
 */
public class StreamIdleTimeoutException extends SocketTimeoutException {

  public StreamIdleTimeoutException(final Duration idle, final Throwable cause) {
    super(
        "The model stopped sending: nothing arrived for "
            + idle.toSeconds()
            + "s, so the stream was abandoned");
    initCause(cause);
  }
}
