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
 * There is no "do not send" state as {@link ReasoningEfforts#NOT_SENT} has: every endpoint accepts
 * its own default, so naming it is the way out.
 */
public final class Temperatures {

  /**
   * The widest range any provider here accepts — OpenAI's and Gemini's. Anthropic stops at 1 and
   * says so itself when asked for more, which the probe reports before anything is stored.
   */
  public static final double MAX = 2.0;

  private Temperatures() {}

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
    return value == null || (value >= 0 && value <= MAX);
  }

  /** {@code value} as it is stored: null for blank. Only for a value {@link #valid} accepted. */
  public static Double parse(final String value) {
    return value == null || value.isBlank() ? null : Double.valueOf(value.trim());
  }
}
