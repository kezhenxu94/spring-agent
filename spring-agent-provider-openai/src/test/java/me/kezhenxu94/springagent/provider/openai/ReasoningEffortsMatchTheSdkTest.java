package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.models.ReasoningEffort;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Core states the reasoning efforts a user may pick as literals, because that list belongs to core
 * and not to a provider — see {@link ReasoningEfforts}. This is where it is held to the SDK's own,
 * since this is the module where the SDK exists.
 *
 * <p>So an effort added upstream fails here rather than going quietly missing from three dropdowns
 * and a tool's parameter description. What the rest of the list's behaviour is — the sentinel,
 * normalising, absent versus invalid — stays asserted in core, where it is implemented.
 */
class ReasoningEffortsMatchTheSdkTest {

  @Test
  @DisplayName("every effort the SDK knows is one a user can pick")
  void coversTheSdk() {
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
}
