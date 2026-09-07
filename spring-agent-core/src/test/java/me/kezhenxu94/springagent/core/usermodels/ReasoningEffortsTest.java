package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The list every dropdown is built from.
 *
 * <p>That it still matches the OpenAI SDK's own is asserted in {@code
 * ReasoningEffortsMatchTheSdkTest} in {@code spring-agent-provider-openai}, which is the module
 * where that SDK exists. What is asserted here is the behaviour core implements: the three states,
 * the sentinel, and what a value typed at a terminal is read as.
 */
class ReasoningEffortsTest {

  @Test
  @DisplayName("the efforts are listed weakest first, as the wire spells them")
  void wireSpelling() {
    assertThat(ReasoningEfforts.VALUES)
        .containsExactly("none", "minimal", "low", "medium", "high", "xhigh", "max");
    // An SDK's own enum constant is HIGH, which no endpoint accepts.
    assertThat(ReasoningEfforts.VALUES).doesNotContain("HIGH");
  }

  @Test
  @DisplayName("the sentinel cannot be mistaken for an effort")
  void sentinelIsDistinct() {
    assertThat(ReasoningEfforts.VALUES).doesNotContain(ReasoningEfforts.NOT_SENT);
    assertThat(ReasoningEfforts.CHOICES).contains(ReasoningEfforts.NOT_SENT);
  }

  @Test
  @DisplayName("a value typed at a terminal is accepted however it was cased")
  void normalizes() {
    assertThat(ReasoningEfforts.valid(" HIGH ")).isTrue();
    assertThat(ReasoningEfforts.normalize(" HIGH ")).isEqualTo("high");
    assertThat(ReasoningEfforts.valid("Not-Sent")).isTrue();
  }

  @Test
  @DisplayName("nothing chosen is not the same as something wrong")
  void absentIsNotInvalid() {
    assertThat(ReasoningEfforts.normalize(null)).isNull();
    assertThat(ReasoningEfforts.normalize("  ")).isNull();
    assertThat(ReasoningEfforts.valid(null)).isFalse();
    assertThat(ReasoningEfforts.valid("highest")).isFalse();
  }
}
