package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;

/**
 * Core's reasoning-effort vocabulary onto Anthropic's thinking budget, and the three states of it.
 *
 * <p>The states are the point. {@code none} and {@code not-sent} are not synonyms — one asks the
 * endpoint to disable thinking, the other asks this project not to mention thinking at all, which
 * is the way out for a gateway that rejects a request carrying a configuration it does not
 * implement. A blank effort is a third thing again: leave whatever the deployment configured alone.
 * Collapsing any two of them is invisible until somebody's gateway starts refusing every message.
 */
class AnthropicThinkingTest {

  private static AnthropicChatOptions applied(final String effort) {
    final var builder = AnthropicChatOptions.builder().model("claude-sonnet-4-5");
    AnthropicThinking.apply(builder, effort);
    return builder.build();
  }

  @Test
  @DisplayName("an effort becomes a thinking budget")
  void anEffortIsABudget() {
    assertThat(applied("medium").getThinking()).isNotNull();
    assertThat(applied("medium").getThinking().isEnabled()).isTrue();
    assertThat(applied("medium").getThinking().asEnabled().budgetTokens()).isEqualTo(4096L);
  }

  @Test
  @DisplayName("the ladder rises, and the top rungs are distinct rather than collapsed")
  void theLadderRises() {
    final var low = applied("low").getThinking().asEnabled().budgetTokens();
    final var high = applied("high").getThinking().asEnabled().budgetTokens();
    final var max = applied("max").getThinking().asEnabled().budgetTokens();
    assertThat(low).isLessThan(high);
    assertThat(high).isLessThan(max);
  }

  @Test
  @DisplayName("'none' disables thinking rather than asking for a budget of zero")
  void noneIsDisabled() {
    // Zero is rejected by the API rather than understood, so `none` has to be the disabled variant.
    final var thinking = applied(AnthropicThinking.NONE).getThinking();
    assertThat(thinking).isNotNull();
    assertThat(thinking.isDisabled()).isTrue();
  }

  @Test
  @DisplayName("'not-sent' and a blank effort both leave the configuration untouched")
  void theTwoWaysOfSayingNothing() {
    assertThat(applied("not-sent").getThinking()).isNull();
    assertThat(applied(null).getThinking()).isNull();
    assertThat(applied("").getThinking()).isNull();
  }

  @Test
  @DisplayName("a configured budget reads back as the effort that would produce it")
  void effortReadsBack() {
    final var options =
        AnthropicChatOptions.builder().model("claude-sonnet-4-5").thinkingEnabled(4096L).build();
    assertThat(AnthropicThinking.effortOf(options)).isEqualTo("medium");

    // A budget between two rungs reports the rung a user would have to pick to get at least that
    // much thinking, rather than the one below it.
    final var between =
        AnthropicChatOptions.builder().model("claude-sonnet-4-5").thinkingEnabled(3000L).build();
    assertThat(AnthropicThinking.effortOf(between)).isEqualTo("medium");
  }

  @Test
  @DisplayName("nothing configured reads back as nothing, not as an effort")
  void nothingConfiguredReadsBackAsNull() {
    assertThat(AnthropicThinking.effortOf(AnthropicChatOptions.builder().build())).isNull();
    assertThat(AnthropicThinking.effortOf(null)).isNull();
  }

  @Test
  @DisplayName("disabled thinking reads back as 'none'")
  void disabledReadsBackAsNone() {
    final var options = AnthropicChatOptions.builder().thinkingDisabled().build();
    assertThat(AnthropicThinking.effortOf(options)).isEqualTo(AnthropicThinking.NONE);
  }
}
