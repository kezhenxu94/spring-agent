package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ModelPricing;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ModelPricing.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

class ModelPricingTest {

  private static final ModelPricing CACHED = new ModelPricing(0.2, 0.4, 0.02, 1.2, Currency.USD);
  private static final ModelPricing UNCACHED = new ModelPricing(0.2, 0.4, null, 1.2, Currency.USD);

  @Test
  @DisplayName("cache reads come out of the prompt tokens and are charged at the cached price")
  void cacheReadsAreChargedAtTheCachedPrice() {
    final var usage = new DefaultUsage(1_000_000, 1_000_000, 2_000_000, null, 800_000L, null);

    // 200k uncached at 0.2, 800k cached at 0.02, 1M out at 1.2.
    assertThat(CACHED.cost(usage, false)).isCloseTo(0.04 + 0.016 + 1.2, within(1e-9));
  }

  @Test
  @DisplayName("the thinking price applies to the uncached part only")
  void thinkingPriceAppliesToUncachedTokens() {
    final var usage = new DefaultUsage(1_000_000, 0, 1_000_000, null, 500_000L, null);

    assertThat(CACHED.cost(usage, true)).isCloseTo(0.2 + 0.01, within(1e-9));
  }

  @Test
  @DisplayName("without a cached price, cache hits are charged at the input price")
  void noCachedPriceChargesTheFullInputPrice() {
    final var usage = new DefaultUsage(1_000_000, 0, 1_000_000, null, 800_000L, null);

    assertThat(UNCACHED.cost(usage, false)).isCloseTo(0.2, within(1e-9));
  }

  @Test
  @DisplayName("a provider reporting no cache reads is charged as before")
  void noCacheReadsReported() {
    final var usage = new DefaultUsage(1_000_000, 1_000_000, 2_000_000);

    assertThat(CACHED.cost(usage, false)).isCloseTo(0.2 + 1.2, within(1e-9));
  }

  @Test
  @DisplayName("cache reads larger than the prompt never make the input negative")
  void cacheReadsOutsideThePromptAreClamped() {
    // What Spring AI's Anthropic model reports: input_tokens already excludes cache reads.
    final var usage = new DefaultUsage(100, 0, 100, null, 1_000_000L, null);

    assertThat(CACHED.cost(usage, false)).isCloseTo(0.02, within(1e-9));
  }

  @Test
  @DisplayName("a call that reported no usage has no cost")
  void noUsageHasNoCost() {
    assertThat(CACHED.cost(null, false)).isNull();
  }
}
