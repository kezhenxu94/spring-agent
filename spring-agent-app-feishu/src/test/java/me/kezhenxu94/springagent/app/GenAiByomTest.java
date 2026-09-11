package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** Bring-your-own-model on a Gemini-only deployment: can a person register an endpoint at all. */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.ai.model.chat=google-genai",
      "spring.ai.model.embedding=google-genai",
      "spring.ai.model.embedding.text=google-genai",
      "spring.ai.model.image=none",
      "spring.ai.model.audio.transcription=none",
      "spring.ai.google.genai.api-key=AIza-test",
      "spring.ai.google.genai.embedding.api-key=AIza-test",
      "spring.ai.google.genai.chat.model=gemini-2.5-pro",
      "spring.ai.google.genai.embedding.text.model=gemini-embedding-001",
      // What turns BYOM on: without it there is no registry and no tools to register with.
      "app.ai.user-models.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "spring.ai.openai.base-url=",
      "spring.ai.openai.api-key=",
      "spring.ai.openai.chat.model="
    })
class GenAiByomTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the context starts with user models on and no OpenAI provider in play")
  void contextLoads() {}

  @Test
  @DisplayName("one dispatcher, over the protocols this classpath actually serves")
  void theDispatcherServesWhatIsOnTheClasspath() {
    assertThat(context.getBeansOfType(UserChatClients.class)).hasSize(1);

    final var clients = context.getBean(UserChatClients.class);
    // Both provider modules are carried here, and both publish their client factory whether or not
    // they built the chat model — which is what makes the protocol select in /config a real choice
    // rather than decoration. Exactly these two, and nothing that no module implements.
    assertThat(clients.providers()).containsExactlyInAnyOrder("openai", "google-genai");
    // spring.ai.model.chat is what says which a row naming none is spoken in — not bean order,
    // which no longer indicates anything now that both are always published.
    assertThat(clients.defaultProvider()).isEqualTo("google-genai");
  }

  @Test
  @DisplayName("a user's row can name a protocol the deployment itself does not speak")
  void aRowMayNameAnotherProtocol() {
    // The whole point of the feature: this deployment runs Gemini, and somebody may still register
    // an OpenAI endpoint of their own and have it spoken to over /chat/completions.
    final var clients = context.getBean(UserChatClients.class);
    final var row =
        UserModelConfig.builder()
            .name("mine")
            .provider("openai")
            .baseUrl("https://gateway.example.com/v1")
            .model("gpt-5.6")
            .build();

    // Building a client resolves options and opens an HTTP client without connecting, so nothing
    // here reaches the endpoint named.
    assertThat(clients.clientFor(row)).isNotNull();
  }

  @Test
  @DisplayName("the model menu is Gemini's too")
  void theMenuIsGeminis() {
    assertThat(context.getBeansOfType(BuiltinModels.class)).hasSize(1);
    assertThat(context.getBean(BuiltinModels.class).defaultModel()).isEqualTo("gemini-2.5-pro");
  }
}
