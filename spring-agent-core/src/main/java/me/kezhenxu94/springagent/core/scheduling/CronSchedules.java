package me.kezhenxu94.springagent.core.scheduling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

/**
 * What a recurring schedule may be.
 *
 * <p>One class rather than a copy per caller, and that is the whole reason it exists: a cron
 * expression reaches this application from the agent's own tools and from a person editing a task
 * in the browser, and two sets of rules about how often a task may fire would mean the floor holds
 * only for whichever route somebody happened to take.
 */
@Slf4j
public final class CronSchedules {

  private CronSchedules() {}

  /**
   * The expression as it will actually be stored: parsed, and raised to the smallest interval this
   * application allows if it asks for one shorter.
   *
   * <p>Raised rather than refused, because "every minute" is a reasonable thing to ask for and a
   * refusal leaves the caller — a model, usually — guessing at a floor nothing told it. The caller
   * compares what comes back with what it gave to find out whether that happened, and says so.
   *
   * @throws IllegalArgumentException when the expression is not a cron expression at all, with the
   *     parser's own message, since that is the part that says which field is wrong
   */
  public static String validated(final String expr) {
    try {
      CronExpression.parse(expr);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "cron expression '" + expr + "' is invalid: " + e.getMessage(), e);
    }
    return enforceMinimumInterval(expr);
  }

  private static String enforceMinimumInterval(final String expr) {
    final var parts = expr.trim().split("\\s+");
    if (parts.length != 6) {
      return expr;
    }
    final var seconds = parts[0];

    // Reject sub-minute intervals on seconds field (e.g. */10, */30)
    if (seconds.startsWith("*/")) {
      parts[0] = "0";
    }

    // Enforce minimum 5-minute interval on minutes field
    if (parts[1].startsWith("*/")) {
      try {
        final var n = Integer.parseInt(parts[1].substring(2));
        if (n < 5) {
          parts[1] = "*/5";
          log.info("Cron '{}' interval too frequent, adjusted minutes field to */5", expr);
        }
      } catch (NumberFormatException ignored) {
      }
    }

    // If seconds was sub-minute but minutes is 0, treat as every-minute — enforce */5 minutes
    if (seconds.startsWith("*/") && parts[1].equals("*")) {
      parts[1] = "*/5";
      log.info("Cron '{}' had sub-minute seconds with wildcard minutes, adjusted to */5", expr);
    }

    return String.join(" ", parts);
  }
}
