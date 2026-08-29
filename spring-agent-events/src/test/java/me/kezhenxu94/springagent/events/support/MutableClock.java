package me.kezhenxu94.springagent.events.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests move by hand.
 *
 * <p>The whole behaviour of this feature is arithmetic on the current time — debounce, the cap on
 * it, the cooldown, the quiet timeout — so a test that could not move time would have to wait out a
 * thirty-second debounce to find out whether debouncing works, and nobody runs a test suite like
 * that. This is why {@code Clock} is a bean rather than {@code Instant.now()} scattered through the
 * module.
 */
public final class MutableClock extends Clock {

  private Instant now;

  public MutableClock(final Instant now) {
    this.now = now;
  }

  public static MutableClock at(final String isoInstant) {
    return new MutableClock(Instant.parse(isoInstant));
  }

  public MutableClock advance(final Duration by) {
    now = now.plus(by);
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.of("UTC");
  }

  @Override
  public Clock withZone(final ZoneId zone) {
    return this;
  }
}
