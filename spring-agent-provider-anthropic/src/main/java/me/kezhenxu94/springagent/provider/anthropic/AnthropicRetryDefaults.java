package me.kezhenxu94.springagent.provider.anthropic;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Raises how many times a request to Claude is retried before this project accepts the Anthropic
 * SDK's own default, for the same reason {@code ToolCallingDefaults} exists in core: an application
 * that merely puts this module on its classpath should get the number a chat agent needs against
 * Anthropic's rate limits, not the one a general-purpose client picked.
 *
 * <p><b>{@code spring.ai.retry.*} does not apply here at all</b>, which is the trap this class
 * exists to avoid re-discovering: that block configures the shared {@code RestClient}/{@code
 * WebClient} retry template the OpenAI-protocol providers sit on, and neither of Anthropic's
 * auto-configurations (plain or {@link AnthropicVertexAutoConfiguration Vertex}) takes a {@code
 * RetryTemplate} — chat goes through {@code com.anthropic.client.AnthropicClient} instead, whose
 * {@code RetryingHttpClient} already retries on 429 and 5xx and already backs off correctly,
 * honouring {@code Retry-After}/{@code Retry-After-Ms} on a 429 and falling back to its own
 * exponential curve otherwise. None of that is configurable from outside the SDK; the one knob it
 * exposes is how many attempts it gets, bound from {@code spring.ai.anthropic.max-retries} by both
 * backends. {@link VertexAnthropicClients} falls back to {@link #DEFAULT_MAX_RETRIES} rather than a
 * constant of its own, so the number cannot drift between the two paths.
 *
 * <p>The SDK's own default is 2, which a burst of 429s from Anthropic's per-model rate limit
 * empties in under a second — the backoff curve barely gets going before the run gives up. {@value
 * #DEFAULT_MAX_RETRIES} leaves it enough attempts to clear a burst that resolves within the
 * minute-long window those limits usually reset on.
 *
 * <p>Contributed as the lowest-precedence property source, so {@code
 * spring.ai.anthropic.max-retries} set anywhere else — {@code application.yaml}, an environment
 * variable — still wins.
 */
public class AnthropicRetryDefaults implements EnvironmentPostProcessor, Ordered {

  /** What a request is retried when {@code spring.ai.anthropic.max-retries} says nothing. */
  public static final int DEFAULT_MAX_RETRIES = 5;

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentAnthropicRetryDefaults",
                Map.of(
                    AnthropicProperties.MAX_RETRIES_PROPERTY,
                    String.valueOf(DEFAULT_MAX_RETRIES))));
  }

  /**
   * Last, so that the property sources this appends after include the ones config data loaded from
   * {@code application.yaml}.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
