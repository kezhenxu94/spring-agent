package me.kezhenxu94.springagent.core.usermodels;

/**
 * The sampling temperature a person may set on a model of their own.
 *
 * <p>A row's own setting exists because the deployment's is copied onto every client built for a
 * row — Spring AI takes per-request options whole, so it has to be — and that value was chosen for
 * the deployment's model. Reasoning models refuse every value but their default: GPT-5 answers
 * {@code temperature: 0} with a 400, so a row pointing at one has to be able to say {@code 1}.
 *
 * <p>Null means the deployment's own, which is what every row stored before this existed means.
 * {@link #NOT_SENT} means none at all, as {@link ReasoningEfforts#NOT_SENT} does for the effort:
 * some models refuse the parameter whatever its value, and the deployment's default is copied onto
 * every request to them unless something says not to.
 */
public final class Temperatures {

  /**
   * The widest range any provider here accepts — OpenAI's and Gemini's. Anthropic stops at 1 and
   * says so itself when asked for more, which the probe reports before anything is stored.
   */
  public static final double MAX = 2.0;

  /**
   * Stored for "send no temperature". Outside the range any value can be, so it can never be taken
   * for one, and a number so it fits the column every backend already has.
   */
  public static final double NOT_SENT = -1.0;

  /**
   * What an endpoint says when it refuses the parameter itself rather than its value: a 400 naming
   * the temperature as deprecated or unsupported. Narrow on purpose — the one thing it licenses is
   * asking again without a temperature, and nothing else is read as that.
   */
  private static final java.util.regex.Pattern REJECTED =
      java.util.regex.Pattern.compile(
          "temperature.{0,80}?(deprecated|unsupported|not supported|does not support|not allowed)"
              + "|(deprecated|unsupported|not supported|does not support|not allowed).{0,80}?"
              + "temperature",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

  private Temperatures() {}

  /** Whether {@code value} is {@link #NOT_SENT}. */
  public static boolean omitted(final Double value) {
    return value != null && value == NOT_SENT;
  }

  /**
   * Whether {@code failure} — an endpoint's error, or a probe's report of one — is a 400 refusing
   * the temperature parameter as such.
   */
  public static boolean rejected(final String failure) {
    return failure != null && failure.contains("400") && REJECTED.matcher(failure).find();
  }

  /** The same, for an exception and everything it was caused by. */
  public static boolean rejected(final Throwable failure) {
    for (var link = failure; link != null; link = link.getCause()) {
      if (rejected(link.getMessage())) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code value} is blank, which means the deployment's own, or a number in range. */
  public static boolean valid(final String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    try {
      return valid(Double.valueOf(value.trim()));
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Whether {@code value} is null, which means the deployment's own, or in range. */
  public static boolean valid(final Double value) {
    return value == null || omitted(value) || (value >= 0 && value <= MAX);
  }

  /** {@code value} as it is stored: null for blank. Only for a value {@link #valid} accepted. */
  public static Double parse(final String value) {
    return value == null || value.isBlank() ? null : Double.valueOf(value.trim());
  }
}
