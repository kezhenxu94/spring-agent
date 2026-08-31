package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The list every dropdown is built from, so what matters here is that it stays the SDK's list and
 * keeps saying what the wire says.
 */
class ReasoningEffortsTest {

  @Test
  @DisplayName("every effort the SDK knows is one a user can pick")
  void coversTheSdk() {
    // The guard the class exists for: an effort added upstream fails here rather than going
    // quietly missing from three dropdowns and the tool's parameter description.
    assertThat(ReasoningEfforts.VALUES).hasSize(ReasoningEffort.Known.values().length);
    assertThat(ReasoningEfforts.VALUES)
        .containsExactly("none", "minimal", "low", "medium", "high", "xhigh", "max");
  }

  @Test
  @DisplayName("the values are what goes on the wire, not the enum constants")
  void wireSpelling() {
    // ReasoningEffort.Known.toString() gives HIGH, which no endpoint accepts.
    assertThat(ReasoningEfforts.VALUES).doesNotContain(ReasoningEffort.Known.HIGH.toString());
    assertThat(ReasoningEfforts.VALUES).contains(ReasoningEffort.HIGH.asString());
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
