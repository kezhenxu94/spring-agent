package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That a deployment which configured nothing is told so at startup, in words naming what to set.
 *
 * <p>Each case here is a question that is otherwise answered at the worst possible moment. A blank
 * credential surfaces from inside an SDK client builder; a blank base URL surfaces as requests to
 * {@code api.openai.com}, which the deployment never chose; a blank <em>model</em> surfaces nowhere
 * at all until a run is made and comes back {@code 400 InvalidParameter: The length of model should
 * be between 1 and 512}.
 *
 * <p>The blank values below are what {@code ${OPENAI_MODEL:}} and its siblings leave behind when
 * nobody sets the variable, which is how every {@code application.yaml} here spells them — so this
 * is the ordinary unconfigured deployment rather than an exotic one.
 */
class OpenAiConnectionCheckTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiProviderAutoConfiguration.class));

  @Test
  @DisplayName("a connection and a model is all it wants")
  void aConfiguredDeploymentStarts() {
    runner
        .withPropertyValues(
            "spring.ai.openai.base-url=https://gateway.example.com/v1",
            "spring.ai.openai.api-key=sk-test",
            "spring.ai.openai.chat.model=gpt-5.6")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("neither a base URL nor a key is not a deployment anybody meant")
  void noConnectionFailsNamingBothWaysToConfigureOne() {
    runner
        .withPropertyValues(
            "spring.ai.openai.base-url=",
            "spring.ai.openai.api-key=",
            "spring.ai.openai.chat.model=gpt-5.6")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("No model endpoint is configured")
                    // Both routes named, because which one applies is not visible from the property
                    // that is missing.
                    .hasMessageContaining("OPENAI_BASE_URL")
                    .hasMessageContaining("DASHSCOPE_API_KEY"));
  }

  @Test
  @DisplayName("a blank model fails on its own, unlike a blank credential")
  void noChatModelFails() {
    // The asymmetry is deliberate: a blank key can mean a local server wanting no auth, but there
    // is no such thing as a default model on the wire.
    runner
        .withPropertyValues(
            "spring.ai.openai.base-url=https://gateway.example.com/v1",
            "spring.ai.openai.api-key=sk-test",
            "spring.ai.openai.chat.model=")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("No chat model is named")
                    .hasMessageContaining("OPENAI_MODEL")
                    .hasMessageContaining("DASHSCOPE_CHAT_MODEL"));
  }

  @Test
  @DisplayName("a blank credential alone is a local server wanting no auth, and starts")
  void noAuthIsALegitimateDeployment() {
    runner
        .withPropertyValues(
            "spring.ai.openai.base-url=http://localhost:8080/v1",
            "spring.ai.openai.api-key=",
            "spring.ai.openai.chat.model=qwen3",
            // The SDK reads OPENAI_API_KEY from the environment when handed none, so the run has to
            // be told there is no credential rather than left to find the developer's own.
            "spring.ai.openai.chat.options.model=qwen3")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
