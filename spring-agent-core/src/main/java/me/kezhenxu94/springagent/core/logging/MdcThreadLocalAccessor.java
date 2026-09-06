package me.kezhenxu94.springagent.core.logging;

import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Teaches Micrometer's context propagation what an MDC is, so Reactor can carry one across the
 * thread boundaries a run crosses.
 *
 * <p>Whole map rather than one key: MDC is a single thread-local holding every key, and restoring
 * one key at a time would leave a worker thread carrying half of the previous run's context. What
 * is captured is therefore whatever {@link RunMdc} set plus anything else the capturing thread had,
 * and what is put back afterwards is exactly what the worker had before the signal.
 *
 * <p>Written here rather than taken from a library because the only shipped one belongs to
 * Micrometer Tracing, which this project does not use.
 */
public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

  /** How the captured map is named in a Reactor context. Ours, and read by nothing else. */
  public static final String KEY = "me.kezhenxu94.springagent.mdc";

  @Override
  public Object key() {
    return KEY;
  }

  @Override
  public Map<String, String> getValue() {
    final var map = MDC.getCopyOfContextMap();
    // Null means there is nothing to capture, which is the contract's way of saying "do not put a
    // value in the context at all". An empty map is not the same thing: it would be captured, and
    // then restored over a worker thread that had context of its own.
    return map == null || map.isEmpty() ? null : map;
  }

  @Override
  public void setValue(final Map<String, String> value) {
    MDC.setContextMap(value);
  }

  @Override
  public void setValue() {
    MDC.clear();
  }

  @Override
  public void restore(final Map<String, String> previousValue) {
    MDC.setContextMap(previousValue);
  }

  @Override
  public void restore() {
    MDC.clear();
  }
}
