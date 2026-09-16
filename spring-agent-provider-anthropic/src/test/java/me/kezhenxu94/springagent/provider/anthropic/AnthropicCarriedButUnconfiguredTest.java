package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That carrying this module costs a deployment that has not configured it nothing.
 *
 * <p>This is the assertion {@code spring-agent-provider-google-genai} needed an {@code
 * AutoConfigurationImportFilter} to be able to make: two of Spring AI's Google GenAI
 * auto-configurations carry only {@code @ConditionalOnClass} and throw from an eager {@code @Bean}
 * with no credential, so simply putting that module on a classpath broke every deployment that had
 * not configured Gemini. Anthropic's is a single auto-configuration gated on {@code
 * spring.ai.model.chat}, so the obvious thing — copying that filter — should be unnecessary here.
 *
 * <p>"Should be" is why this test exists rather than a comment. If Spring AI ever ungates it, or if
 * the SDK starts refusing to build a client without a credential, this fails and the filter becomes
 * necessary — which is a decision worth being told about rather than discovering in a deployment
 * that had nothing to do with Claude.
 *
 * <p>The second test is the one that matters for the four server applications: they name {@code
 * spring.ai.model.chat} explicitly, so this module must contribute no {@code ChatModel} there.
 */
class AnthropicCarriedButUnconfiguredTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  AnthropicChatAutoConfiguration.class,
                  AnthropicVertexAutoConfiguration.class,
                  AnthropicProviderAutoConfiguration.class));

  @Test
  @DisplayName("another provider serving chat leaves this module contributing no model")
  void anotherProviderWins() {
    runner
        .withPropertyValues("spring.ai.model.chat=openai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
              // The rejection reader is still there: it is asked about every failure of every run,
              // including runs another provider served, and answers about nothing but Anthropic's
              // own exception type.
              assertThat(context).hasSingleBean(AnthropicProviderRejection.class);
            });
  }

  @Test
  @DisplayName("no credential and no backend is a startup this module does not break")
  void nothingConfigured() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=openai", AnthropicProperties.API_KEY_PROPERTY + "=")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
