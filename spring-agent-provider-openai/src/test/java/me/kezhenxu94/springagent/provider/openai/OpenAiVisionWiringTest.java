package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Whether there is a vision client here, which decides whether core offers {@code RecognizeImage}.
 *
 * <p>The blank case is the one worth asserting, for the reason {@code
 * ConditionalOnNonBlankProperty} gives: every {@code application.yaml} spells this {@code
 * ${OPENAI_VISION_MODEL:}}, so an unset variable leaves the property present and empty, and
 * {@code @ConditionalOnProperty} would call that configured and build a client asking for a model
 * called {@code ""}.
 *
 * <p>Nothing here reaches an endpoint: building the client resolves options and opens an HTTP
 * client without connecting.
 */
class OpenAiVisionWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  // The chat model this provider's vision client copies its connection from, plus
                  // the ToolCallingManager that model insists on — Spring AI's own, since what is
                  // under test here is the condition rather than anything core replaces.
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiProviderAutoConfiguration.class))
          .withPropertyValues(
              "spring.ai.openai.base-url=https://gateway.example.com/v1",
              "spring.ai.openai.api-key=sk-test",
              "spring.ai.openai.chat.options.model=gpt-5.6");

  @Test
  @DisplayName("naming a vision model is what creates the client core conditions the tool on")
  void aModelNamedIsAClient() {
    runner
        .withPropertyValues("spring.ai.openai.vision.model=gpt-5.6")
        .run(context -> assertThat(context).hasBean("visionChatClient"));
  }

  @Test
  @DisplayName("naming none is no client, not a client onto the chat model")
  void noModelIsNoClient() {
    // Deliberate even though the chat model would very likely answer about an image: whether the
    // model behind base-url can see images is something only the operator knows.
    runner.run(context -> assertThat(context).doesNotHaveBean("visionChatClient"));
  }

  @Test
  @DisplayName("a model named as empty is no client either")
  void aBlankModelIsNoClient() {
    runner
        .withPropertyValues("spring.ai.openai.vision.model=")
        .run(context -> assertThat(context).doesNotHaveBean("visionChatClient"));
  }

  @Test
  @DisplayName("the vision client keeps the application's connection but not its reasoning effort")
  void theEffortIsNotCarriedOver() {
    final var defaults =
        OpenAiChatOptions.builder()
            .baseUrl("https://gateway.example.com/v1")
            .apiKey("sk-test")
            .model("gpt-5.6")
            .reasoningEffort("xhigh")
            .temperature(0.0)
            .build();

    final var options =
        OpenAiProviderAutoConfiguration.visionOptions(
            defaults,
            new OpenAiVisionProperties(
                "qwen3-vl-plus", "https://vision.example.com/v1", "sk-eyes"));

    // An effort configured for the model that runs the turns is not a statement about a vision
    // model at another endpoint, and a gateway that reads it as its own thinking parameter refuses
    // every call — see visionOptions for the error that led here.
    assertThat(options.getReasoningEffort()).isNull();
    assertThat(options.getModel()).isEqualTo("qwen3-vl-plus");
    assertThat(options.getBaseUrl()).isEqualTo("https://vision.example.com/v1");
    assertThat(options.getApiKey()).isEqualTo("sk-eyes");
    // Everything else the deployment configured still has to survive the copy.
    assertThat(options.getTemperature()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("a vision block naming no endpoint of its own keeps the application's")
  void aBlankEndpointIsTheApplicationsOwn() {
    final var defaults =
        OpenAiChatOptions.builder()
            .baseUrl("https://gateway.example.com/v1")
            .apiKey("sk-test")
            .model("gpt-5.6")
            .build();

    final var options =
        OpenAiProviderAutoConfiguration.visionOptions(
            defaults, new OpenAiVisionProperties("gpt-5.6-vision", "", ""));

    assertThat(options.getBaseUrl()).isEqualTo("https://gateway.example.com/v1");
    assertThat(options.getApiKey()).isEqualTo("sk-test");
    assertThat(options.getModel()).isEqualTo("gpt-5.6-vision");
  }
}
