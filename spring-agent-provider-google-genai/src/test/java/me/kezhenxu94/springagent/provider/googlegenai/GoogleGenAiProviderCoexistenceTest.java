package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Two providers on one classpath, and the configuration a deployment actually writes to mix them.
 *
 * <p>Every model auto-configuration Spring AI ships is {@code matchIfMissing = true}, so "unset
 * means openai" — which is what every {@code application.yaml} in this repository relied on — stops
 * being true the moment a second provider is on the classpath. Unset then means <em>both</em>, and
 * the context has two {@code ChatModel} beans. These tests are what say so, and what pin the keys
 * an operator has to name instead.
 *
 * <p>The embedding pair is the subtle one, and the reason there are four tests for it. The two
 * providers read <em>different keys</em>: OpenAI's auto-configuration is gated on {@code
 * spring.ai.model.embedding} and Google's on {@code spring.ai.model.embedding.text}. Since both are
 * {@code matchIfMissing}, naming either one alone silences one provider and leaves the other
 * matching by default — which happens to give the right answer in each direction, but only by
 * accident of which key was named.
 *
 * <p><b>So the rule these tests establish, and the one the yaml follows, is to set both keys to the
 * same value.</b> That is correct in both directions and does not depend on knowing which provider
 * reads which key, which is exactly the thing nobody will remember.
 */
class GoogleGenAiProviderCoexistenceTest {

  private static final String GEMINI_KEY = GoogleGenAiProperties.API_KEY_PROPERTY + "=AIza-test";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ToolCallingAutoConfiguration.class,
                  OpenAiChatAutoConfiguration.class,
                  OpenAiEmbeddingAutoConfiguration.class,
                  GoogleGenAiChatAutoConfiguration.class,
                  GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
                  GoogleGenAiTextEmbeddingAutoConfiguration.class))
          .withPropertyValues(
              GEMINI_KEY,
              // Spelled out because an ApplicationContextRunner never invokes an
              // EnvironmentPostProcessor, so GoogleGenAiDefaults — which is what fills this in for
              // a real application, and the reason a deployment names its key once — does not run
              // here. GoogleGenAiDefaultsOrderingTest is what covers that half.
              GoogleGenAiDefaults.EMBEDDING_API_KEY + "=AIza-test",
              "spring.ai.google.genai.chat.model=gemini-2.5-pro",
              "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
              "spring.ai.openai.base-url=https://gateway.example.com/v1",
              "spring.ai.openai.api-key=sk-test",
              "spring.ai.openai.chat.options.model=gpt-5.6",
              "spring.ai.openai.embedding.options.model=text-embedding-3-small");

  @Test
  @DisplayName("naming no provider leaves both, which is why the yaml must now name them")
  void unsetMeansBoth() {
    runner.run(
        context -> {
          // Not an assertion about what is desirable — an assertion about what happens, so that
          // the comment block in five application.yaml files has something to point at.
          assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
          assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2);
        });
  }

  @Test
  @DisplayName("Gemini chat with DashScope-style embeddings: the configuration this module is for")
  void geminiChatWithOpenAiProtocolEmbeddings() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=google-genai",
            "spring.ai.model.embedding=openai",
            "spring.ai.model.embedding.text=openai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
              assertThat(context).hasSingleBean(GoogleGenAiChatModel.class);
              assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
              assertThat(context).hasSingleBean(OpenAiEmbeddingModel.class);
            });
  }

  @Test
  @DisplayName("and the other way round, so neither direction is an accident")
  void openAiChatWithGeminiEmbeddings() {
    runner
        .withPropertyValues(
            "spring.ai.model.chat=openai",
            "spring.ai.model.embedding=google-genai",
            "spring.ai.model.embedding.text=google-genai")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
              assertThat(context).hasSingleBean(OpenAiChatModel.class);
              assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
              assertThat(context).hasSingleBean(GoogleGenAiTextEmbeddingModel.class);
            });
  }

  @Test
  @DisplayName("spring.ai.model.embedding=openai leaves Gemini's embedding model as well")
  void theObviousKeyDoesNotSilenceGemini() {
    // The trap worth a test of its own. This reads like "use OpenAI embeddings" and is what an
    // operator writes first, because it is the key that exists in every other Spring AI example —
    // but Google's auto-configuration never looks at it, so its model is built too.
    runner
        .withPropertyValues("spring.ai.model.chat=openai", "spring.ai.model.embedding=openai")
        .run(context -> assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2));
  }

  @Test
  @DisplayName("spring.ai.model.embedding.text=google-genai leaves OpenAI's as well")
  void theOtherObviousKeyDoesNotSilenceOpenAi() {
    // The mirror image, so the asymmetry cannot be read as being about one provider. Setting the
    // key Gemini reads turns Gemini on and leaves OpenAI matching by default.
    runner
        .withPropertyValues(
            "spring.ai.model.chat=openai", "spring.ai.model.embedding.text=google-genai")
        .run(context -> assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2));
  }
}
