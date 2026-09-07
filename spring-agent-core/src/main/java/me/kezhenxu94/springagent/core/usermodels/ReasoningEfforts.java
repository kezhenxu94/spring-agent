package me.kezhenxu94.springagent.core.usermodels;

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
   * Every effort the wire accepts, weakest first, spelled as it goes on the wire.
   *
   * <p>Literals rather than a provider SDK's constants, because this list is core's: it is what
   * three dropdowns are drawn from and what a stored {@code reasoningEffort} is validated against,
   * on a deployment whose provider module core knows nothing about. Lowercase deliberately — an
   * SDK's own enum is a Java enum whose {@code toString} yields {@code HIGH}, which no endpoint
   * accepts.
   *
   * <p>That the list still matches the SDK is asserted where the SDK is on the classpath: {@code
   * ReasoningEffortsMatchTheSdkTest} in {@code spring-agent-provider-openai}. An effort added
   * upstream fails the build there rather than going quietly missing from every dropdown, and this
   * comment is the pointer to it — a list of literals with nothing checking them would drift.
   */
  public static final List<String> VALUES =
      List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

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
