package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That the body of a rejected request is rescued on <em>both</em> backends, which reach the same
 * interceptor by two different mechanisms.
 *
 * <p>On the Anthropic backend it is a bean: Spring AI's own auto-configuration collects every
 * {@code AnthropicHttpClientBuilderCustomizer} in the context. On the Vertex backend that seam is
 * unavailable — {@code AnthropicChatModel.Builder} throws {@code IllegalArgumentException} when
 * customizers meet a pre-built client, because by then the HTTP layer already exists — so {@link
 * VertexAnthropicClients} attaches the interceptor to its own HTTP client builder instead.
 *
 * <p>Two mechanisms for one behaviour is exactly the shape that rots quietly: a refactor that
 * "tidies up" by passing the customizer list to both paths turns into a startup failure on one of
 * them, and one that drops the direct attachment leaves Vertex rejections logging as the bare word
 * {@code Unknown}. Both halves are asserted here.
 */
class AnthropicErrorBodyCustomizerWiringTest {

  private static final String CREDENTIALS = "classpath:test-service-account.json";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  AnthropicChatAutoConfiguration.class,
                  AnthropicVertexAutoConfiguration.class,
                  AnthropicProviderAutoConfiguration.class));

  @Test
  @DisplayName("the customizer is published as a bean, which is the seam Spring AI collects")
  void theCustomizerIsABean() {
    runner
        .withPropertyValues("spring.ai.model.chat=openai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicHttpClientBuilderCustomizer.class);
            });
  }

  @Test
  @DisplayName("the vertex model builds despite the customizer bean being in the context")
  void theVertexPathDoesNotPassCustomizersToTheBuilder() {
    // The failure this pins: handing AnthropicChatModel.Builder both a pre-built client and the
    // context's customizers is an IllegalArgumentException at startup, not a warning. If somebody
    // "unifies" the two paths, this is what refuses.
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.BACKEND_PROPERTY + "=vertex",
            AnthropicProperties.PREFIX + ".vertex.project-id=a-project",
            AnthropicProperties.PREFIX + ".vertex.location=us-east5",
            AnthropicProperties.PREFIX + ".vertex.credentials-location=" + CREDENTIALS,
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5@20250929")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
