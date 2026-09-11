package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * That the whole server starts talking only to Gemini, with no {@code OPENAI_*} variable set.
 *
 * <p>A started context rather than a slice, because what breaks a Gemini-only deployment is never
 * the provider module — it is some other part of the application reaching for a bean that belongs
 * to the provider it is *not* using. Two did:
 *
 * <ul>
 *   <li>{@code FeishuCardElements} took {@code OpenAiChatProperties} as a required constructor
 *       argument, to label the thinking panel with the configured reasoning effort. That bean
 *       exists only while Spring AI's OpenAI chat auto-configuration ran, so a chat surface with no
 *       interest in model providers failed the entire context over a label. Making it nullable was
 *       only half a fix — it stopped the crash and left the panel blank on a provider that has the
 *       concept — so the reading moved behind core's {@link ReasoningEffortInForce}, which each
 *       provider translates its own spelling into.
 *   <li>Transcription has one provider and every application file left it at {@code openai}, so
 *       core registered {@code TranscribeAudio} against a client pointed at nothing — {@code
 *       TRANSCRIPTION_MODEL_PROVIDER=none} is what a deployment serving none has to set.
 * </ul>
 *
 * <p>Neither is visible from a provider test, and neither would have been caught by anything short
 * of starting this.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.ai.model.chat=google-genai",
      "spring.ai.model.embedding=google-genai",
      "spring.ai.model.embedding.text=google-genai",
      "spring.ai.model.image=google-genai",
      "spring.ai.model.audio.transcription=none",
      "spring.ai.google.genai.api-key=AIza-test",
      "spring.ai.google.genai.embedding.api-key=AIza-test",
      "spring.ai.google.genai.chat.model=gemini-2.5-pro",
      "spring.ai.google.genai.chat.thinking-level=medium",
      "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
      // Deliberately blank, exactly as a deployment that never held an OpenAI credential has them.
      "spring.ai.openai.base-url=",
      "spring.ai.openai.api-key=",
      "spring.ai.openai.chat.model=",
      "spring.ai.openai.embedding.base-url=",
      "spring.ai.openai.embedding.api-key=",
      "spring.ai.openai.embedding.model="
    })
class GenAiOnlyStartupTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the context starts at all, which is the whole point")
  void contextLoads() {}

  @Test
  @DisplayName("exactly one chat and one embedding model, both Gemini's")
  void theModelsAreGeminis() {
    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(GoogleGenAiChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(GoogleGenAiTextEmbeddingModel.class)).hasSize(1);
  }

  @Test
  @DisplayName("no OpenAI chat properties bean exists, which is what used to fail the card surface")
  void noOpenAiChatProperties() {
    assertThat(context.getBeansOfType(OpenAiChatProperties.class)).isEmpty();
  }

  @Test
  @DisplayName("the reply card is still built")
  void theCardSurfaceStillWorks() {
    // The bean whose required constructor argument broke this.
    assertThat(
            context.getBean(
                me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardElements.class))
        .isNotNull();
  }

  @Test
  @DisplayName("the card reports Gemini's thinking level, rather than nothing at all")
  void theCardReportsTheThinkingLevel() {
    // The point of ReasoningEffortInForce being a core contract. Making the OpenAI properties
    // nullable stopped the crash but left the thinking panel blank on Gemini, which was wrong
    // twice over: Gemini has this concept, and the provider already knows how to say it in core's
    // words. Anything else here means a surface is reading a provider's configuration again.
    final var inForce = context.getBean(ReasoningEffortInForce.class);
    assertThat(inForce.forUser("u1"))
        .as("spring.ai.google.genai.chat.thinking-level=medium should reach the card as 'medium'")
        .isEqualTo("medium");
  }

  @Test
  @DisplayName("TranscribeAudio is absent rather than present and broken")
  void noTranscriptionTool() {
    assertThat(context.getBeansOfType(TranscriptionModel.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Object.class))
        .noneMatch(name -> name.toLowerCase().contains("audiotranscriptiontool"));
  }
}
