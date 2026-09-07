package me.kezhenxu94.springagent.provider.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Whether there is a vision client, which decides whether there is a {@code RecognizeImage} tool.
 *
 * <p>The blank case is the one worth a test, and is the reason this file exists.
 * {@code @ConditionalOnProperty} matches a property that is <em>present and empty</em> — anything
 * that is not the literal {@code false} — and every {@code application.yaml} here spells this
 * setting {@code ${DASHSCOPE_VISION_MODEL:}}, so an unset variable leaves it exactly that. Gated
 * that way, this bean was built with an empty model name and every call came back as {@code 400
 * InvalidParameter: The length of model should be between 1 and 512} — which reads to the agent as
 * a broken endpoint to retry rather than as a feature nobody configured. See {@code
 * ConditionalOnNonBlankProperty} in core.
 */
class DashScopeVisionWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DashScopeProviderAutoConfiguration.class))
          .withPropertyValues("spring.ai.dashscope.api-key=sk-dashscope");

  @Test
  @DisplayName("a model named is a client, under the name core conditions the tool on")
  void aModelNamedIsAClient() {
    runner
        .withPropertyValues("spring.ai.dashscope.vision.model=qwen3-vl-plus")
        .run(
            context -> {
              assertThat(context).hasBean("visionChatClient");
              assertThat(context.getBean("visionChatClient")).isInstanceOf(ChatClient.class);
            });
  }

  @Test
  @DisplayName("no model named is no client at all")
  void noModelIsNoClient() {
    runner.run(context -> assertThat(context).doesNotHaveBean("visionChatClient"));
  }

  @Test
  @DisplayName("a model named as empty is no client either, which @ConditionalOnProperty got wrong")
  void aBlankModelIsNoClient() {
    // Exactly what ${DASHSCOPE_VISION_MODEL:} leaves behind when nobody sets the variable.
    runner
        .withPropertyValues("spring.ai.dashscope.vision.model=")
        .run(context -> assertThat(context).doesNotHaveBean("visionChatClient"));
  }

  @Test
  @DisplayName("only blank means unset: this property is a model name, not a flag")
  void onlyBlankMeansUnset() {
    // `false` is the one value @ConditionalOnProperty rejects, and here it must not be treated
    // specially: this property names a model, so the only thing that is not a value is no value.
    // A deployment whose model is literally called `false` gets a client, and an endpoint's
    // rejection of that name is its own business rather than something to guess at here.
    //
    // Asserting it is what keeps the two conditions from being swapped back for one another: a
    // test that only covered blank would pass under either.
    runner
        .withPropertyValues("spring.ai.dashscope.vision.model=false")
        .run(context -> assertThat(context).hasBean("visionChatClient"));
  }
}
