package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A fourth provider on the classpath, and the configuration a Claude deployment actually writes.
 *
 * <p>Every model auto-configuration Spring AI ships is {@code matchIfMissing = true}, so a kind of
 * model that names no provider gets <em>every</em> provider that serves it and the application
 * fails to start with two {@code ChatModel} beans. {@code GoogleGenAiProviderCoexistenceTest}
 * established that when the third arrived; this is the same assertion with the fourth, and it is
 * why every {@code application.yaml} here names {@code spring.ai.model.chat} explicitly.
 *
 * <p><b>The Anthropic case has something the others do not: a deployment on Claude is always a
 * mixed one.</b> Anthropic serves no embeddings, no images and no transcription, so naming {@code
 * anthropic} for chat and nothing else leaves an application with no {@code EmbeddingModel} and no
 * knowledge base. That is not a limitation to be worked around — the switches are per kind
 * precisely so the sets mix — but it does mean "run it on Claude" is never one variable, and the
 * test that says so belongs here rather than in a README nobody reads twice.
 */
class AnthropicProviderCoexistenceTest {

  private static final String ANTHROPIC_KEY = AnthropicProperties.API_KEY_PROPERTY + "=sk-ant-test";

  private static final String OPENAI_KEY = "spring.ai.openai.api-key=sk-test";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class,
                  AnthropicChatAutoConfiguration.class,
                  AnthropicVertexAutoConfiguration.class,
                  AnthropicProviderAutoConfiguration.class))
          .withPropertyValues(ANTHROPIC_KEY, OPENAI_KEY);

  @Test
  @DisplayName("naming no chat provider gets both of them, which is why the yaml names one")
  void unsetMeansBoth() {
    runner.run(
        context -> {
          // Not an assertion about what is desirable — it is the trap, written down. A deployment
          // that forgets the switch gets this, and the failure it eventually produces says nothing
          // about providers.
          assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
        });
  }

  @Test
  @DisplayName("Claude chat beside OpenAI embeddings, which is what a Claude deployment looks like")
  void claudeChatWithOpenAiEmbeddings() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=anthropic",
            "spring.ai.model.embedding=openai",
            "spring.ai.model.embedding.text=openai",
            AnthropicProperties.CHAT_MODEL_PROPERTY + "=claude-sonnet-4-5")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AnthropicChatModel.class);
              assertThat(context).doesNotHaveBean(OpenAiChatModel.class);
              // The half that makes it a working deployment rather than a chat model on its own.
              assertThat(context).hasSingleBean(OpenAiEmbeddingModel.class);
            });
  }

  @Test
  @DisplayName("naming another provider for chat leaves no Claude model at all")
  void openAiChatIsUndisturbed() {
    runner
        .withPropertyValues("spring.ai.model.chat=openai", "spring.ai.model.embedding=openai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(OpenAiChatModel.class);
              assertThat(context).doesNotHaveBean(AnthropicChatModel.class);
            });
  }
}
