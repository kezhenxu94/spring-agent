package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * That the whole server starts on Claude, with no {@code OPENAI_*} chat model and no Gemini key.
 *
 * <p>The sibling of {@code GenAiOnlyStartupTest}, and for the same reason: what breaks a deployment
 * on a given provider is never the provider module, it is some other part of the application
 * reaching for a bean belonging to the provider it is <em>not</em> using. That test records two
 * such bugs; a started context is the only thing that finds the third.
 *
 * <p>It is deliberately <b>not</b> "Anthropic only", because no such deployment exists. Anthropic
 * serves no embeddings, so a Claude server always names another provider for them — the reason the
 * switches are per kind — and a test written as "only" would simply fail to start, teaching
 * nothing. This is the configuration an operator actually writes: Claude for chat, an
 * OpenAI-protocol endpoint for embeddings, and neither images nor transcription.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.ai.model.chat=anthropic",
      "spring.ai.model.embedding=openai",
      "spring.ai.model.embedding.text=openai",
      "spring.ai.model.image=none",
      "spring.ai.model.audio.transcription=none",
      "spring.ai.anthropic.api-key=sk-ant-test",
      "spring.ai.anthropic.chat.model=claude-sonnet-4-5",
      // The embedding half, which is not optional on this provider.
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=sk-test",
      "spring.ai.openai.embedding.model=text-embedding-3-small",
      // Deliberately blank, exactly as a deployment that never held an OpenAI chat credential has
      // them — and blank rather than absent, since that is what ${OPENAI_BASE_URL:} resolves to.
      "spring.ai.openai.base-url=",
      "spring.ai.openai.api-key=",
      "spring.ai.openai.chat.model=",
      "spring.ai.google.genai.api-key="
    })
class AnthropicStartupTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the context starts at all, which is the whole point")
  void contextLoads() {}

  @Test
  @DisplayName("exactly one chat model, Anthropic's, beside one embedding model that is not")
  void theModelsAreTheOnesNamed() {
    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(AnthropicChatModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(1);
    assertThat(context.getBeansOfType(OpenAiEmbeddingModel.class)).hasSize(1);
  }

  @Test
  @DisplayName("no OpenAI chat properties bean exists, which is what used to fail the card surface")
  void noOpenAiChatProperties() {
    // Naming openai for embeddings does not bring the chat block back, so a surface reading it
    // would still break here.
    assertThat(context.getBeansOfType(OpenAiChatProperties.class)).isEmpty();
  }

  @Test
  @DisplayName("the reply card is still built, and asking it for an effort does not throw")
  void theCardSurfaceStillWorks() {
    assertThat(
            context.getBean(
                me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardElements.class))
        .isNotNull();

    // Anthropic configures a thinking *budget* rather than one of core's words, so the answer here
    // is whatever AnthropicThinking reads back — null with nothing configured, which is a state the
    // card has to survive. Throwing would be the failure; a null is not one.
    final var inForce = context.getBean(ReasoningEffortInForce.class);
    assertThatCode(() -> inForce.forUser("u1")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "neither GenerateImage nor TranscribeAudio is offered, since Anthropic serves neither")
  void theAbsentToolsAreAbsent() {
    // A tool the model can see is a tool it will try, and one that can only fail teaches it nothing
    // except to try again. Anthropic has no image or transcription API at all, so on a deployment
    // that names no other provider for them the tools must not exist.
    assertThat(context.getBeansOfType(ImageModel.class)).isEmpty();
    assertThat(context.getBeansOfType(TranscriptionModel.class)).isEmpty();
    assertThat(context.getBeanNamesForType(Object.class))
        .noneMatch(name -> name.toLowerCase().contains("audiotranscriptiontool"))
        .noneMatch(name -> name.toLowerCase().contains("imagegenerationtools"));
  }

  @Test
  @DisplayName("Spring AI's own Anthropic properties are bound beside this project's")
  void bothPropertyBeansShareThePrefix() {
    // spring.ai.anthropic is bound twice — by Spring AI for the connection and the chat options,
    // and by AnthropicProperties for `backend` and the vertex block. Two binders on one prefix is
    // intentional and works because unknown fields are ignored; this is what says so.
    assertThat(context.getBean(AnthropicChatProperties.class).getModel())
        .isEqualTo("claude-sonnet-4-5");
    assertThat(
            context
                .getBean(me.kezhenxu94.springagent.provider.anthropic.AnthropicProperties.class)
                .backend())
        .isEqualTo("anthropic");
  }
}
