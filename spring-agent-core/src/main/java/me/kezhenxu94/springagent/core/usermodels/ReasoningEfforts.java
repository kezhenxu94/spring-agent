package me.kezhenxu94.springagent.core.usermodels;

import com.openai.models.ReasoningEffort;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * How hard a model should think, as something to be picked off a list rather than typed.
 *
 * <p>Spring AI takes {@code reasoning_effort} as a bare {@code String}, so nothing upstream says
 * which strings an endpoint will accept — and the answer being a free-text field is how a user ends
 * up with an endpoint that fails on every message because they wrote {@code highest}. This is that
 * list, in one place, for the dropdown on every surface and for the tool's error message.
 *
 * <p>Three states, and all three are reachable:
 *
 * <ul>
 *   <li><b>absent</b> (null or blank) — leave the application's own {@code
 *       spring.ai.openai.chat.options.reasoning-effort} in place, which is what every row stored
 *       before this existed does;
 *   <li><b>one of {@link #VALUES}</b> — send that;
 *   <li><b>{@link #NOT_SENT}</b> — send no {@code reasoning_effort} at all, which is the way out
 *       for a gateway that rejects the parameter rather than ignoring it. Needed as its own state
 *       because absent already means something else, and because {@code none} is a real value the
 *       newer models act on rather than a way of omitting it.
 * </ul>
 */
public final class ReasoningEfforts {

  /**
   * The value standing for "do not send the parameter". Hyphenated so it cannot collide with an
   * effort the SDK adds later — those are single lowercase words — and spelled the same wherever it
   * appears: in the column, in a dropdown's option value, and as something a user may type after
   * {@code /config}.
   */
  public static final String NOT_SENT = "not-sent";

  /**
   * Every effort the SDK knows, weakest first, as the wire spells them.
   *
   * <p>Built from the {@link ReasoningEffort} constants rather than from {@code
   * ReasoningEffort.Known.values()}, which looks like the obvious source and is the wrong one:
   * {@code Known} is a Java enum whose {@code toString} yields the constant's name, so it gives
   * {@code HIGH} where the wire value is {@code high}. {@code ReasoningEffortsTest} checks that
   * this list still covers every {@code Known} value, so an effort the SDK adds fails the build
   * here rather than going quietly missing from every dropdown.
   */
  public static final List<String> VALUES =
      Stream.of(
              ReasoningEffort.NONE,
              ReasoningEffort.MINIMAL,
              ReasoningEffort.LOW,
              ReasoningEffort.MEDIUM,
              ReasoningEffort.HIGH,
              ReasoningEffort.XHIGH,
              ReasoningEffort.MAX)
          .map(ReasoningEffort::asString)
          .toList();

  /** Everything a user may choose, in the order to offer it. */
  public static final List<String> CHOICES =
      Stream.concat(VALUES.stream(), Stream.of(NOT_SENT)).toList();

  private ReasoningEfforts() {}

  /** Whether {@code effort} is one of {@link #CHOICES}. False for null and blank. */
  public static boolean valid(final String effort) {
    return effort != null && CHOICES.contains(normalize(effort));
  }

  /**
   * {@code effort} as it is stored: trimmed and lowercased, since a user typing {@code HIGH} at a
   * terminal means the same thing as the dropdown's {@code high}. Null and blank stay null, which
   * is the "leave the application's setting alone" state.
   */
  public static String normalize(final String effort) {
    if (effort == null || effort.isBlank()) {
      return null;
    }
    return effort.trim().toLowerCase(Locale.ROOT);
  }

  /** The choices as one line, for telling somebody what they should have written. */
  public static String listed() {
    return String.join(", ", CHOICES);
  }
}
