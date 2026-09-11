package me.kezhenxu94.springagent.provider.googlegenai;

import com.google.common.base.Strings;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;

/**
 * Core's reasoning-effort vocabulary onto Gemini's, which is two fields and a shorter ladder.
 *
 * <p>{@link ReasoningEfforts#VALUES} is {@code none, minimal, low, medium, high, xhigh, max} — the
 * OpenAI ladder, because that is the provider this project grew up on, and core owns it so that a
 * user's stored choice means the same thing whichever provider serves their runs. Gemini offers
 * {@code MINIMAL, LOW, MEDIUM, HIGH} and nothing above, so the top three all land on {@code HIGH}:
 * asking for more than an endpoint has is not an error, it is the most it will do.
 *
 * <p>{@code none} is the one that is not a level at all. Gemini's enum has no "off" — {@code
 * MINIMAL}'s own documentation says it "does not guarantee that thinking is off" — and the way to
 * actually disable it is a thinking budget of zero. So {@code none} sets the budget and leaves the
 * level alone, which is the only combination that means what the user asked for.
 *
 * <p>{@code not-sent} sets neither field, which is a third state and not a synonym for {@code
 * none}: it is how somebody stops the parameter being sent at all, for a gateway that rejects a
 * request carrying a thinking configuration it does not implement. See {@link ReasoningEfforts}.
 */
final class GoogleGenAiThinking {

  /**
   * What each of core's efforts means here. {@code xhigh} and {@code max} deliberately share {@code
   * HIGH} with {@code high}; {@code none} is absent because it is a budget rather than a level.
   */
  private static final Map<String, GoogleGenAiThinkingLevel> LEVELS =
      Map.of(
          "minimal", GoogleGenAiThinkingLevel.MINIMAL,
          "low", GoogleGenAiThinkingLevel.LOW,
          "medium", GoogleGenAiThinkingLevel.MEDIUM,
          "high", GoogleGenAiThinkingLevel.HIGH,
          "xhigh", GoogleGenAiThinkingLevel.HIGH,
          "max", GoogleGenAiThinkingLevel.HIGH);

  /** Gemini's documented way to switch thinking off, which no value of the enum expresses. */
  static final int NO_THINKING_BUDGET = 0;

  static final String NONE = "none";

  private GoogleGenAiThinking() {}

  /**
   * Applies {@code effort} to {@code builder}, leaving both fields untouched where the user chose
   * nothing or chose {@link ReasoningEfforts#NOT_SENT} — so that whatever the deployment configured
   * survives a copy of its own options.
   */
  static void apply(final GoogleGenAiChatOptions.Builder builder, final String effort) {
    if (Strings.isNullOrEmpty(effort) || ReasoningEfforts.NOT_SENT.equals(effort)) {
      return;
    }
    final var normalized = effort.toLowerCase(Locale.ROOT);
    if (NONE.equals(normalized)) {
      builder.thinkingBudget(NO_THINKING_BUDGET);
      return;
    }
    final var level = LEVELS.get(normalized);
    if (level != null) {
      builder.thinkingLevel(level);
    }
    // An effort that is on no list is left to the endpoint rather than guessed at, the same way an
    // unrecognised image size is. Core normalises what it stores, so this only happens to a row
    // written by an older or a newer version of this project.
  }

  /** The level {@code effort} names, or null where it names none. For tests and for logging. */
  static GoogleGenAiThinkingLevel levelOf(final String effort) {
    return effort == null ? null : LEVELS.get(effort.toLowerCase(Locale.ROOT));
  }

  /**
   * The mapping walked backwards: what {@code options} were configured with, said in core's words.
   *
   * <p>Needed because {@code ReasoningEffortInForce} answers in the vocabulary the person choosing
   * saw, which is core's ladder and not Gemini's enum.
   *
   * <p>{@code HIGH} answers {@code high} — not {@code xhigh} or {@code max}, which also map onto it
   * — because {@code high} is the word that round-trips, and a label claiming more than was asked
   * for would be wrong in the direction that matters.
   */
  static String effortOf(final GoogleGenAiChatOptions options) {
    if (options == null) {
      return null;
    }
    if (options.getThinkingLevel() == null) {
      return Integer.valueOf(NO_THINKING_BUDGET).equals(options.getThinkingBudget()) ? NONE : null;
    }
    return switch (options.getThinkingLevel()) {
      case MINIMAL -> "minimal";
      case LOW -> "low";
      case MEDIUM -> "medium";
      case HIGH -> "high";
      case THINKING_LEVEL_UNSPECIFIED -> null;
    };
  }
}
