package me.kezhenxu94.springagent.provider.anthropic;

import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.google.common.base.Strings;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import org.springframework.ai.anthropic.AnthropicChatOptions;

/**
 * Core's reasoning-effort vocabulary onto Anthropic's, which is not a ladder at all but a token
 * budget.
 *
 * <p>{@link ReasoningEfforts#VALUES} is {@code none, minimal, low, medium, high, xhigh, max} — the
 * OpenAI ladder, because that is the provider this project grew up on, and core owns it so a user's
 * stored choice means the same thing whichever provider serves their runs. Anthropic expresses
 * extended thinking as {@code budget_tokens}: how many tokens the model may spend reasoning before
 * it answers. So the mapping here is a table of budgets rather than a table of enum values, and the
 * numbers are a judgement rather than a translation — they are chosen to rise roughly as the words
 * do and to stay clear of the small end, where a budget too low to finish a thought is worse than
 * none at all.
 *
 * <p>Two constraints from the API shape this table and are the reason it is not simply doubled at
 * each step:
 *
 * <ul>
 *   <li>{@code budget_tokens} must be at least 1024, so {@code minimal} starts there rather than
 *       lower;
 *   <li>it must be strictly less than {@code max_tokens}, so every budget here sits below the 8192
 *       the applications configure. A deployment raising one should raise the other.
 * </ul>
 *
 * <p>{@code none} maps to {@link ThinkingConfigDisabled}, which is Anthropic's own way of saying
 * off, and not to a budget of zero — zero is rejected rather than understood.
 *
 * <p>{@code not-sent} sets nothing, which is a third state and not a synonym for {@code none}: it
 * is how somebody stops the parameter being sent at all, for a gateway in front of Claude that
 * rejects a request carrying a thinking configuration it does not implement. See {@link
 * ReasoningEfforts}.
 */
final class AnthropicThinking {

  /**
   * What each of core's efforts means here, in thinking tokens. {@code none} is absent because it
   * is a disabled configuration rather than a budget.
   */
  private static final Map<String, Long> BUDGETS =
      Map.of(
          "minimal", 1024L,
          "low", 2048L,
          "medium", 4096L,
          "high", 6144L,
          "xhigh", 8192L,
          "max", 12288L);

  static final String NONE = "none";

  private AnthropicThinking() {}

  /**
   * Applies {@code effort} to {@code builder}, leaving the thinking configuration untouched where
   * the user chose nothing or chose {@link ReasoningEfforts#NOT_SENT} — so that whatever the
   * deployment configured under {@code spring.ai.anthropic.chat.thinking} survives a copy of its
   * own options.
   */
  static void apply(final AnthropicChatOptions.Builder builder, final String effort) {
    if (Strings.isNullOrEmpty(effort) || ReasoningEfforts.NOT_SENT.equals(effort)) {
      return;
    }
    final var normalized = effort.toLowerCase(Locale.ROOT);
    if (NONE.equals(normalized)) {
      builder.thinking(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()));
      return;
    }
    final var budget = BUDGETS.get(normalized);
    if (budget == null) {
      // An effort core knows and this table does not: leave the configuration alone rather than
      // guess. ReasoningEffortsMatchTheSdkTest guards the OpenAI list against drifting; here the
      // consequence of drift is only that a new word means "as configured".
      return;
    }
    builder.thinking(
        ThinkingConfigParam.ofEnabled(
            ThinkingConfigEnabled.builder().budgetTokens(budget).build()));
  }

  /**
   * Which of core's efforts the deployment's own options amount to, for {@code
   * ProviderChatClients.configuredEffort}.
   *
   * <p>Read back rather than remembered, because the deployment configures a budget and not a word.
   * The answer is the weakest effort whose budget is at least what is configured, so a budget
   * between two rungs reports the rung a user would have to pick to get at least that much
   * thinking. Null where nothing is configured, and {@code none} where thinking is explicitly
   * disabled.
   */
  static String effortOf(final AnthropicChatOptions defaults) {
    if (defaults == null || defaults.getThinking() == null) {
      return null;
    }
    final var thinking = defaults.getThinking();
    if (thinking.isDisabled()) {
      return NONE;
    }
    if (!thinking.isEnabled()) {
      // Adaptive, or a shape this SDK added later: it is a real configuration but not one of core's
      // words, and inventing one would put a value in a dropdown that does not round-trip.
      return null;
    }
    final var configured = thinking.asEnabled().budgetTokens();
    return ReasoningEfforts.VALUES.stream()
        .filter(BUDGETS::containsKey)
        .filter(effort -> BUDGETS.get(effort) >= configured)
        .findFirst()
        .orElse("max");
  }
}
