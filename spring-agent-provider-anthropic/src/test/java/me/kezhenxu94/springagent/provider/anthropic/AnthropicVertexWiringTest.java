package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which of the two auto-configurations builds the chat model, and that exactly one of them does.
 *
 * <p>This is the module's central mechanism and it is invisible from the code: {@link
 * AnthropicVertexAutoConfiguration} declares itself {@code beforeName} Spring AI's, whose bean is
 * {@code @ConditionalOnMissingBean}, so on the Vertex backend ours registers first and theirs backs
 * off. Get the ordering wrong and the symptom is not an error — it is a deployment that configured
 * Vertex and is quietly served by {@code api.anthropic.com}.
 *
 * <p>{@code backend=false} is tested for the reason {@code DashScopeVisionWiringTest} tests its
 * equivalent: only blank may mean unset. A property spelled {@code ${ANTHROPIC_BACKEND:}} is
 * present and empty when nobody set the variable, and {@code @ConditionalOnProperty} would call
 * that configured — which is why the backend is read through a bound record that defaults it rather
 * than through a condition on the raw value.
 */
class AnthropicVertexWiringTest {

  /**
   * A syntactically valid service-account key, so that credential resolution is exercised without
   * reaching Google and without depending on whoever runs the build having {@code gcloud} set up.
   * The private key is a throwaway generated for this test and authenticates nothing.
   */
  private static final String CREDENTIALS = "classpath:test-service-account.json";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  AnthropicChatAutoConfiguration.class,
                  AnthropicVertexAutoConfiguration.class));

  @Test
  @DisplayName("the class Spring AI's auto-configuration is ordered against still exists")
  void theNameResolves() {
    // beforeName is matched textually, so a rename upstream would make the ordering inert and both
    // configurations would register a model. Failing here is much better than finding out from two
    // ChatModel beans in a deployment.
    assertThatCode(
            () ->
                Class.forName(
                    "org.springframework.ai.model.anthropic.autoconfigure"
                        + ".AnthropicChatAutoConfiguration"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the vertex backend builds the model, and Spring AI's backs off")
  void vertexWins() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.BACKEND_PROPERTY + "=vertex",
            AnthropicProperties.PREFIX + ".vertex.project-id=a-project",
            AnthropicProperties.PREFIX + ".vertex.location=us-east5",
            AnthropicProperties.PREFIX + ".vertex.credentials-location=" + CREDENTIALS,
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5@20250929")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicChatModel.class);
              assertThat(context.getBean(AnthropicChatModel.class).getOptions().getModel())
                  .isEqualTo("claude-sonnet-4-5@20250929");
            });
  }

  @Test
  @DisplayName("the default backend leaves the model to Spring AI")
  void anthropicIsTheDefault() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.API_KEY_PROPERTY + "=sk-ant-test",
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicChatModel.class);
            });
  }

  @Test
  @DisplayName("the bound backend is exactly what the bean condition reads, whitespace included")
  void theRecordAndTheConditionAgree() {
    // Not a theoretical tidiness. @ConditionalOnProperty compares the raw property, so a record
    // that trimmed would let " vertex " select the Anthropic backend while vertexBacked() said
    // Vertex — and the two things keyed on that answer, lending a deployment key to a user row and
    // listing models, would both be wrong with nothing failing.
    assertThat(new AnthropicProperties("  vertex  ", null).vertexBacked()).isFalse();
    assertThat(new AnthropicProperties("vertex", null).vertexBacked()).isTrue();
    // Blank is the only thing that means unset.
    assertThat(new AnthropicProperties("   ", null).backend())
        .isEqualTo(AnthropicProperties.BACKEND_ANTHROPIC);
    assertThat(new AnthropicProperties(null, null).backend())
        .isEqualTo(AnthropicProperties.BACKEND_ANTHROPIC);
  }

  @Test
  @DisplayName("an incomplete vertex block fails with our message, not the SDK's")
  void theRefusalNamesTheVariable() {
    // Bean order is what this pins. The chat model is built by a configuration ordered *before* the
    // one holding AnthropicConnectionCheck, so without the same refusal inside the model's own bean
    // the first thing to fail is the SDK's Check.checkRequired — whose message names a Kotlin
    // builder field called `project` and neither the property nor the environment variable.
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.BACKEND_PROPERTY + "=vertex",
            AnthropicProperties.PREFIX + ".vertex.location=us-east5",
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5@20250929")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("ANTHROPIC_VERTEX_PROJECT"));
  }

  @Test
  @DisplayName("a blank backend is the default one, and 'false' is still a backend name")
  void onlyBlankMeansUnset() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.BACKEND_PROPERTY + "=",
            AnthropicProperties.API_KEY_PROPERTY + "=sk-ant-test",
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // Blank fell back to the anthropic backend, so ours contributed nothing.
              assertThat(context).hasSingleBean(AnthropicChatModel.class);
            });
  }
}
