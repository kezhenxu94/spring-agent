package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * That {@code spring.ai.anthropic.chat.*} still reaches something even where {@code
 * AnthropicChatAutoConfiguration} itself never runs, which is the gap a BYOM Anthropic row would
 * otherwise fall into silently on any deployment not chatting through Claude.
 */
class AnthropicUserModelChatPropertiesTest {

  private static final String OPENAI_KEY = "spring.ai.openai.api-key=sk-test";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  AnthropicChatAutoConfiguration.class,
                  AnthropicVertexAutoConfiguration.class,
                  AnthropicProviderAutoConfiguration.class))
          .withPropertyValues(OPENAI_KEY);

  @Test
  @DisplayName("chatting through OpenAI still binds spring.ai.anthropic.chat.* for BYOM rows")
  void boundEvenWithoutAnthropicAsTheDeploymentsOwnModel() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=openai",
            "spring.ai.anthropic.chat.max-tokens=12345",
            "spring.ai.anthropic.chat.cache-options.strategy=system_only")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicChatProperties.class);
              final var properties = context.getBean(AnthropicChatProperties.class);
              assertThat(properties.getMaxTokens()).isEqualTo(12345);
              assertThat(properties.toOptions().getMaxTokens()).isEqualTo(12345);
            });
  }

  @Test
  @DisplayName("chatting through Anthropic keeps Spring AI's own bean rather than a second one")
  void backsOffWhenAnthropicIsTheDeploymentsOwnModel() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            AnthropicProperties.API_KEY_PROPERTY + "=sk-ant-test",
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5",
            "spring.ai.anthropic.chat.max-tokens=12345")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // Exactly one bean either way: ours backs off rather than shadowing Spring AI's,
              // which additionally carries the connection details AnthropicChatAutoConfiguration
              // folds in.
              assertThat(context).hasSingleBean(AnthropicChatProperties.class);
              assertThat(context.getBean(AnthropicChatProperties.class).getMaxTokens())
                  .isEqualTo(12345);
            });
  }
}
