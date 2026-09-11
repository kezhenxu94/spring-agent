package me.kezhenxu94.springagent.appcli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * That a laptop talking only to Gemini starts, with no {@code OPENAI_*} variable set at all.
 *
 * <p>Worth a whole application context rather than a slice, because the two things that went wrong
 * here are both about what the *rest* of the application does with a provider it is not using:
 *
 * <ul>
 *   <li>{@code spring.ai.openai.base-url} was a required placeholder. Placeholders resolve when
 *       something binds the block, and Spring AI's transcription auto-configuration binds {@code
 *       OpenAiCommonProperties} even when chat and embeddings have gone to Gemini — so this context
 *       failed on a variable it had no reason to want.
 *   <li>That same auto-configuration then published a {@code TranscriptionModel} built against an
 *       endpoint of nothing, and core registers {@code TranscribeAudio} on the mere existence of
 *       one. The model was offered a tool that could only ever fail, which is precisely what {@code
 *       ModelToolsConfiguration} exists to avoid.
 * </ul>
 *
 * <p>Both are configuration rather than code, so only a started context says they are fixed.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Everything Gemini, and deliberately not one OPENAI_* or EMBEDDING_* value: the point is
      // that a deployment which never held those credentials starts anyway.
      "spring.ai.model.chat=google-genai",
      "spring.ai.model.embedding=google-genai",
      "spring.ai.model.embedding.text=google-genai",
      "spring.ai.model.image=google-genai",
      "spring.ai.model.audio.transcription=none",
      "spring.ai.google.genai.api-key=AIza-test",
      "spring.ai.google.genai.chat.model=gemini-2.5-pro",
      "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-cli-genai-test.db",
      "app.ai.tools.shell.type=none"
    })
class GenAiOnlyStartupTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the models are Gemini's, and there is exactly one of each")
  void theModelsAreGeminis() {
    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(GoogleGenAiChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(GoogleGenAiTextEmbeddingModel.class)).hasSize(1);
  }

  @Test
  @DisplayName("no OpenAI model is built, and none was configured")
  void noOpenAiModels() {
    assertThat(context.getBeanNamesForType(ChatModel.class))
        .noneMatch(name -> name.toLowerCase().contains("openai"));
    assertThat(context.getBeanNamesForType(EmbeddingModel.class))
        .noneMatch(name -> name.toLowerCase().contains("openai"));
  }

  @Test
  @DisplayName("TranscribeAudio is absent rather than present and broken")
  void noTranscriptionTool() {
    // The tool is registered on @ConditionalOnBean(TranscriptionModel.class), so the switch above
    // is the only thing standing between this deployment and a tool that 404s on every call.
    assertThat(context.getBeansOfType(TranscriptionModel.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Object.class))
        .noneMatch(name -> name.toLowerCase().contains("audiotranscriptiontool"));
  }
}
