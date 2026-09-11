package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.DispatchingUserChatClients;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEffortInForce;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * What a surface is told about how hard a run is being asked to think.
 *
 * <p>{@code UserChatClients.effortInForce} had no test of its own until this one: it was covered
 * only through {@code FeishuCardUpdaterReasoningTest}, which is to say a chat surface was the only
 * thing asserting a provider's behaviour. That is the wrong way round, and it is also how the label
 * came to be read off {@code OpenAiChatProperties} directly — a shortcut that made the card wrong
 * on one provider and stopped the application starting on another.
 */
class OpenAiEffortInForceTest {

  private static final String APP_EFFORT = "xhigh";

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static OpenAiChatOptions appOptions() {
    return OpenAiChatOptions.builder()
        .baseUrl("https://app/v1")
        .apiKey("k")
        .model("app-model")
        .reasoningEffort(APP_EFFORT)
        .build();
  }

  /**
   * A registry in which {@code userId} is on the application's model with an effort of their own.
   */
  private static UserModelRegistry registryWhere(final String userId, final String effort) {
    final var repo = mock(UserModelConfigRepo.class);
    if (userId != null) {
      final var row =
          UserModelConfig.builder()
              .id(UserModelConfig.idFor(userId, "@"))
              .ownerId(userId)
              .name("@")
              .reasoningEffort(effort)
              .activated(true)
              .build();
      when(repo.findByOwnerId(userId)).thenReturn(List.of(row));
    }
    return new UserModelRegistry(repo, new AesGcmSealer(KEY, "t"), 3);
  }

  /**
   * The contract as {@code OpenAiProviderAutoConfiguration} publishes it: this provider's client
   * factory behind core's real dispatcher.
   *
   * <p>Whole chain rather than a stub of either half, because that is where the behaviour now lives
   * — resolving the active row and honouring {@code not-sent} moved to the dispatcher when a row
   * gained a provider, and only the client building stayed here.
   */
  private static ReasoningEffortInForce effortInForce(final UserModelRegistry registry) {
    if (registry == null) {
      // A deployment with no user models: nothing resolves a row, and the configured effort is the
      // answer for everybody.
      return userId -> APP_EFFORT;
    }
    final var openai =
        new OpenAiUserChatClients(
            registry, appOptions(), ToolCallingManager.builder().build(), List.of(), 3);
    final var dispatching =
        new DispatchingUserChatClients(
            ChatClient.builder(OpenAiChatModel.builder().options(appOptions()).build()).build(),
            registry,
            List.of(openai),
            openai.provider());
    return dispatching::effortInForce;
  }

  @Test
  @DisplayName("with no user models at all, every run reports the deployment's effort")
  void withoutUserModels() {
    // The case that used to have no answer: the bean this stands for is published whether or not
    // app.ai.user-models.encryption-key is set, precisely so a surface need not know.
    final var inForce = effortInForce((UserModelRegistry) null);
    assertThat(inForce.forUser("u1")).isEqualTo(APP_EFFORT);
    assertThat(inForce.forUser(null)).isEqualTo(APP_EFFORT);
  }

  @Test
  @DisplayName("a user who chose nothing gets the deployment's effort")
  void aUserWithNoChoice() {
    final var inForce = effortInForce(registryWhere(null, null));
    assertThat(inForce.forUser("nobody")).isEqualTo(APP_EFFORT);
  }

  @Test
  @DisplayName("a user who chose an effort of their own is reported with theirs")
  void aUsersOwnEffort() {
    // Reading configuration here would label their run with a number that had nothing to do with
    // it, which is the whole reason this is asked of the provider rather than of a property.
    final var inForce = effortInForce(registryWhere("u1", "minimal"));
    assertThat(inForce.forUser("u1")).isEqualTo("minimal");
  }

  @Test
  @DisplayName("a user who turned the parameter off is reported as nothing, not as the default")
  void notSentIsNull() {
    // Three states, not two: not-sent means the field never reaches the endpoint, and a surface
    // must show no effort rather than fall back to the deployment's.
    final var inForce = effortInForce(registryWhere("u1", ReasoningEfforts.NOT_SENT));
    assertThat(inForce.forUser("u1")).isNull();
  }

  @Test
  @DisplayName("an unreadable row costs a label, never a run")
  void neverThrows() {
    final var broken = mock(UserModelConfigRepo.class);
    when(broken.findByOwnerId("u1")).thenThrow(new IllegalStateException("database is on fire"));
    final var registry = new UserModelRegistry(broken, new AesGcmSealer(KEY, "t"), 3);
    assertThat(effortInForce(registry).forUser("u1")).isEqualTo(APP_EFFORT);
  }
}
