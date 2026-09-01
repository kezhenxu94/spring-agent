package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * That the options this package builds its clients from say where the application's endpoint is.
 *
 * <p>The case that matters is the ordinary one: a deployment configuring {@code
 * spring.ai.openai.base-url} and {@code api-key} beside {@code spring.ai.openai.chat.*}, as
 * documented. The options the {@code OpenAiChatModel} bean carries hold none of that, so a client
 * built from them alone reaches either nothing or the public OpenAI endpoint — see {@link
 * ApplicationEndpoint}.
 */
class ApplicationEndpointTest {

  private final OpenAiCommonProperties common = new OpenAiCommonProperties();
  private final OpenAiChatProperties chat = new OpenAiChatProperties();

  @Test
  @DisplayName("the connection configured beside the chat block reaches the options")
  void resolvesTheConnection() {
    common.setBaseUrl("https://gateway/v1");
    common.setApiKey("gateway-key");
    common.setTimeout(Duration.ofMinutes(30));
    chat.setModel("app-model");
    chat.setTemperature(0.0);

    final var options = resolve();

    assertThat(options.getBaseUrl()).isEqualTo("https://gateway/v1");
    assertThat(options.getApiKey()).isEqualTo("gateway-key");
    // Dropped, this is a user's client timing out after the SDK's 60 seconds mid-stream.
    assertThat(options.getTimeout()).isEqualTo(Duration.ofMinutes(30));
    // What the chat block already carried has to survive the copy.
    assertThat(options.getModel()).isEqualTo("app-model");
    assertThat(options.getTemperature()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("the chat block wins where it states the connection itself")
  void chatWins() {
    common.setBaseUrl("https://gateway/v1");
    common.setApiKey("gateway-key");
    chat.setBaseUrl("https://chat-gateway/v1");
    chat.setApiKey("chat-key");

    final var options = resolve();

    assertThat(options.getBaseUrl()).isEqualTo("https://chat-gateway/v1");
    assertThat(options.getApiKey()).isEqualTo("chat-key");
  }

  private OpenAiChatOptions resolve() {
    return ApplicationEndpoint.resolve(chat.toOptions(), common, chat);
  }
}
