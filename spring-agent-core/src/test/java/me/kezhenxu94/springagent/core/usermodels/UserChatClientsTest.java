package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * What a user's choices do to the options a run is made with, and to how many clients exist.
 *
 * <p>Nothing here talks to a model: building a client resolves options and opens an HTTP client
 * without connecting, so the endpoints named are never called.
 */
class UserChatClientsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final OpenAiChatOptions appOptions =
      OpenAiChatOptions.builder()
          .baseUrl("https://app/v1")
          .apiKey("app-key")
          .model("app-model")
          .reasoningEffort("medium")
          .temperature(0.3)
          .build();

  private final OpenAiChatModel appModel = OpenAiChatModel.builder().options(appOptions).build();

  private final UserChatClients clients =
      new UserChatClients(
          ChatClient.builder(appModel).build(),
          new UserModelRegistry(mock(UserModelConfigRepo.class), new AesGcmSealer(KEY, "t"), 3),
          appModel,
          List.of(),
          10);

  @Test
  @DisplayName("a model with no effort of its own keeps everything the application configured")
  void inherits() {
    final var options = optionsFor(null);

    assertThat(options.getReasoningEffort()).isEqualTo("medium");
    // The reason the options are copied rather than built: everything else has to survive too.
    assertThat(options.getTemperature()).isEqualTo(0.3);
  }

  @Test
  @DisplayName("a chosen effort is what gets sent")
  void sends() {
    assertThat(optionsFor("high").getReasoningEffort()).isEqualTo("high");
  }

  @Test
  @DisplayName("the sentinel stops the parameter being sent at all")
  void clears() {
    assertThat(optionsFor(ReasoningEfforts.NOT_SENT).getReasoningEffort()).isNull();
    // And nothing else went with it.
    assertThat(optionsFor(ReasoningEfforts.NOT_SENT).getTemperature()).isEqualTo(0.3);
  }

  @Test
  @DisplayName("two efforts on one endpoint are two clients, one effort is one")
  void cachedPerEffort() {
    final var high = clients.clientFor(builtin("app-model", "high"));
    final var low = clients.clientFor(builtin("app-model", "low"));

    assertThat(high).isNotSameAs(low);
    assertThat(clients.clientFor(builtin("app-model", "high"))).isSameAs(high);
  }

  @Test
  @DisplayName("a row naming no model is the application's model, thinking as the user asked")
  void defaultRowFollowsTheDeployment() {
    // UserModelRegistry.DEFAULT_ROW: no base URL and no model, so both come from the application —
    // which is why it must resolve to the very same client as naming that model explicitly.
    final var byName = clients.clientFor(builtin("app-model", "high"));

    assertThat(clients.clientFor(builtin(null, "high"))).isSameAs(byName);
  }

  private OpenAiChatOptions optionsFor(final String effort) {
    return UserChatClients.optionsFor(
        appOptions, new UserChatClients.Endpoint("https://own/v1", "own-key", "own-model", effort));
  }

  private static UserModelConfig builtin(final String model, final String effort) {
    return UserModelConfig.builder().name("@").model(model).reasoningEffort(effort).build();
  }
}
