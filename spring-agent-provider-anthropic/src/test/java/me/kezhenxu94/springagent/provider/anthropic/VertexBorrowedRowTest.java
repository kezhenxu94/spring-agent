package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.micrometer.observation.ObservationRegistry;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

/**
 * That a Vertex-backed deployment can serve the commonest row there is.
 *
 * <p>{@code UserModelRegistry.DEFAULT_ROW} is written whenever somebody picks a reasoning effort
 * for the application's own model — no endpoint, no model, no credential, just an effort. It is the
 * row every {@code /config} user ends up with, and on a Vertex deployment the first design could
 * not serve it at all: borrowing meant lending the deployment's Anthropic API key, and a Vertex
 * deployment has none. {@code DispatchingUserChatClients} caught the refusal and fell back, so runs
 * kept working — but the chosen effort was dropped on every one of them and each logged a stack
 * trace.
 *
 * <p>The repair is to borrow the built <em>clients</em> rather than a credential, which is right on
 * both backends. This test is the Vertex half, because that is the half that was broken.
 */
class VertexBorrowedRowTest {

  private static final String CREDENTIALS = "classpath:test-service-account.json";

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final AesGcmSealer sealer = new AesGcmSealer(KEY, "t");

  private final UserModelRegistry registry =
      new UserModelRegistry(mock(UserModelConfigRepo.class), sealer, 3);

  /** A Vertex-backed application model, built exactly as the auto-configuration builds it. */
  private AnthropicChatModel vertexBackedApplicationModel() {
    final var clients =
        VertexAnthropicClients.create(
            new AnthropicProperties.Vertex("a-project", "global", CREDENTIALS),
            null,
            null,
            Map.of(),
            ObservationRegistry.NOOP,
            null);
    return AnthropicChatModel.builder()
        .anthropicClient(clients.sync())
        .anthropicClientAsync(clients.async())
        .options(AnthropicChatOptions.builder().model("claude-sonnet-5").build())
        .build();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<AnthropicChatModel> provider(final AnthropicChatModel model) {
    final var objectProvider = (ObjectProvider<AnthropicChatModel>) mock(ObjectProvider.class);
    org.mockito.Mockito.when(objectProvider.getIfAvailable()).thenReturn(model);
    return objectProvider;
  }

  @Test
  @DisplayName("a reasoning-effort row is served on Vertex, rather than refused")
  void theDefaultRowIsServedOnVertex() {
    final var application = vertexBackedApplicationModel();
    final var clients =
        new AnthropicUserChatClients(
            registry,
            application.getOptions(),
            provider(application),
            ToolCallingManager.builder().build(),
            List.of(),
            4);

    final var effortRow =
        UserModelConfig.builder()
            .name(UserModelRegistry.DEFAULT_ROW)
            .reasoningEffort("high")
            .build();

    // The assertion is that this does not throw. Before the repair it threw IllegalStateException
    // on every run of every user who had ever picked an effort.
    assertThatCode(() -> clients.clientFor(effortRow)).doesNotThrowAnyException();
    assertThat(clients.clientFor(effortRow)).isNotNull();
  }

  @Test
  @DisplayName("the borrowed client is cached per effort, not rebuilt per message")
  void borrowedClientsAreCached() {
    final var application = vertexBackedApplicationModel();
    final var clients =
        new AnthropicUserChatClients(
            registry,
            application.getOptions(),
            provider(application),
            ToolCallingManager.builder().build(),
            List.of(),
            4);

    final var row =
        UserModelConfig.builder()
            .name(UserModelRegistry.DEFAULT_ROW)
            .reasoningEffort("medium")
            .build();

    assertThat(clients.clientFor(row)).isSameAs(clients.clientFor(row));
  }

  @Test
  @DisplayName("a user's own Anthropic key is still honoured on a Vertex deployment")
  void anOwnKeyStillWorksBesideVertex() {
    // The other half of the boundary: a Vertex-backed deployment does not stop somebody bringing
    // their own Anthropic endpoint, it only stops them borrowing a credential that does not exist.
    final var application = vertexBackedApplicationModel();
    final var clients =
        new AnthropicUserChatClients(
            registry,
            application.getOptions(),
            provider(application),
            ToolCallingManager.builder().build(),
            List.of(),
            4);

    final var ownRow =
        UserModelConfig.builder()
            .id(UserModelConfig.idFor("u1", "mine"))
            .ownerId("u1")
            .name("mine")
            .provider(AnthropicUserChatClients.PROVIDER)
            .model("claude-sonnet-4-5@20250929")
            .apiKeyCipher(sealer.seal("sk-ant-the-users-own"))
            .activated(true)
            .build();

    assertThatCode(() -> clients.clientFor(ownRow)).doesNotThrowAnyException();
  }
}
