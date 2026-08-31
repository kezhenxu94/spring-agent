package me.kezhenxu94.springagent.core.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests move by hand.
 *
 * <p>What {@code ScheduledTaskSweeper} does is arithmetic on the current time, and the case worth
 * testing most — an occurrence missed while the process was down — is by definition a large jump in
 * it. A test that could not move time would have to be left running for three days to find out
 * whether catch-up works.
 *
 * <p>A copy of the one in {@code spring-agent-events} rather than a shared one: test sources are
 * not published between modules, and events depends on core, so it could not go the other way
 * either.
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
