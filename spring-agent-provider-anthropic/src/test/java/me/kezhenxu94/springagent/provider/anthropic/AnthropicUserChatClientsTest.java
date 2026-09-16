package me.kezhenxu94.springagent.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.List;
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
 * What a row registered against Anthropic is actually reached with, and what it refuses.
 *
 * <p>Anthropic has one well-known host, so a row here carries a key and a model and <em>no base URL
 * at all</em> — the same shape a Gemini row has, and the same trap. Read as "the application's own
 * endpoint", which is what a blank base URL meant while a deployment had one provider, it would
 * borrow a credential this deployment may not have and ignore the key the person typed.
 *
 * <p>The refusal is this module's own case and is the reason bring-your-own-model stops at
 * Anthropic's API. On a Vertex-backed deployment there is no Anthropic key to lend — the credential
 * is a Google one — so a row with nothing of its own cannot be served here at all, and saying so is
 * what lets {@code DispatchingUserChatClients} fall back to the application's own client with a
 * warning instead of building one that 401s on every message.
 */
class AnthropicUserChatClientsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final AesGcmSealer sealer = new AesGcmSealer(KEY, "t");

  private final UserModelRegistry registry =
      new UserModelRegistry(mock(UserModelConfigRepo.class), sealer, 3);

  /** A deployment serving chat from another provider: nothing to copy, nothing to lend. */
  private AnthropicUserChatClients withoutADeploymentAnthropic() {
    return new AnthropicUserChatClients(
        registry, null, noModel(), ToolCallingManager.builder().build(), List.of(), 4);
  }

  /** Stands in for a context where Spring AI's Anthropic auto-configuration backed off. */
  @SuppressWarnings("unchecked")
  private static ObjectProvider<AnthropicChatModel> noModel() {
    final var provider = (ObjectProvider<AnthropicChatModel>) mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  private UserModelConfig row(final String baseUrl) {
    return UserModelConfig.builder()
        .id(UserModelConfig.idFor("u1", "mine"))
        .ownerId("u1")
        .name("mine")
        .provider(AnthropicUserChatClients.PROVIDER)
        .baseUrl(baseUrl)
        .model("claude-sonnet-4-5")
        .apiKeyCipher(sealer.seal("sk-ant-the-users-own"))
        .activated(true)
        .build();
  }

  @Test
  @DisplayName("no base URL is needed, because there is nothing to type")
  void noBaseUrlIsNeeded() {
    assertThat(withoutADeploymentAnthropic().requiresBaseUrl()).isFalse();
  }

  @Test
  @DisplayName("a row with no base URL still uses the key the person typed")
  void aRowWithoutABaseUrlKeepsItsOwnKey() {
    final var endpoint = AnthropicUserChatClients.endpointFor(registry, null, row(null));

    assertThat(endpoint.apiKey()).isEqualTo("sk-ant-the-users-own");
    assertThat(endpoint.baseUrl()).isNull();
    assertThat(endpoint.model()).isEqualTo("claude-sonnet-4-5");
  }

  @Test
  @DisplayName("a base URL is honoured where one is given, for a gateway re-serving the protocol")
  void aBaseUrlIsHonouredWhenGiven() {
    final var endpoint =
        AnthropicUserChatClients.endpointFor(
            registry, null, row("https://claude.gateway.example.com"));

    assertThat(endpoint.baseUrl()).isEqualTo("https://claude.gateway.example.com");
    assertThat(endpoint.apiKey()).isEqualTo("sk-ant-the-users-own");
  }

  @Test
  @DisplayName(
      "a row carrying no credential carries no credential, which is what marks it borrowed")
  void aRowWithNoCredentialBorrows() {
    // UserModelRegistry.DEFAULT_ROW: the application's own model with an effort of the user's
    // choosing. A null key here is not a gap to fill from configuration — it is the signal to reuse
    // the application's already-built clients, which is the only thing that works on Vertex.
    final var defaultRow =
        UserModelConfig.builder()
            .name(UserModelRegistry.DEFAULT_ROW)
            .reasoningEffort("low")
            .build();

    final var endpoint =
        AnthropicUserChatClients.endpointFor(
            registry, AnthropicChatOptions.builder().model("claude-haiku-4-5").build(), defaultRow);

    assertThat(endpoint.apiKey()).isNull();
    assertThat(endpoint.model()).isEqualTo("claude-haiku-4-5");
    assertThat(endpoint.reasoningEffort()).isEqualTo("low");
  }

  @Test
  @DisplayName("borrowed options carry no connection, so the built clients are not rebuilt")
  void borrowedOptionsCarryNoConnection() {
    // The bug this pins: stamping a blank apiKey into the options makes AnthropicChatModel rebuild
    // both clients from nothing rather than reuse the ones it was handed. On Vertex that swaps a
    // Google-signed connection for one pointed at api.anthropic.com with no credential at all.
    final var options =
        AnthropicUserChatClients.optionsFor(
            AnthropicChatOptions.builder().model("claude-haiku-4-5").build(),
            new AnthropicUserChatClients.Endpoint(null, null, "claude-haiku-4-5", "low"));

    assertThat(options.getApiKey()).isNull();
    assertThat(options.getBaseUrl()).isNull();
    // The effort still applies — that is the whole point of the row.
    assertThat(options.getThinking()).isNotNull();
  }

  @Test
  @DisplayName("with no model of this module's to borrow, a bare row is refused rather than broken")
  void nothingToLendIsRefused() {
    // Only where another provider serves chat, which is much narrower than it used to be: a
    // Vertex-backed deployment *does* have something to lend now, namely its own clients.
    // Throwing is the contract — the dispatcher turns it into the application's own client.
    final var defaultRow = UserModelConfig.builder().name(UserModelRegistry.DEFAULT_ROW).build();

    assertThatThrownBy(() -> withoutADeploymentAnthropic().clientFor(defaultRow))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another provider");
  }

  @Test
  @DisplayName("the endpoint's connection travels in the options, so both SDK clients agree")
  void theConnectionIsInTheOptions() {
    // Not a stylistic choice: AnthropicChatModel builds a synchronous and an asynchronous client
    // independently, each from these options. A connection carried any other way would reach one
    // of them and not the other — see VertexAnthropicClientsTest.
    final var options =
        AnthropicUserChatClients.optionsFor(
            null,
            new AnthropicUserChatClients.Endpoint(
                "https://gw.example.com", "sk-ant-x", "claude-sonnet-4-5", "medium"));

    assertThat(options.getApiKey()).isEqualTo("sk-ant-x");
    assertThat(options.getBaseUrl()).isEqualTo("https://gw.example.com");
    assertThat(options.getModel()).isEqualTo("claude-sonnet-4-5");
    assertThat(options.getThinking()).isNotNull();
  }

  @Test
  @DisplayName("the deployment's own options are copied rather than rebuilt")
  void theDeploymentsOptionsSurvive() {
    // Spring AI takes supplied options whole rather than merging them with the model's, so anything
    // not copied here is silently dropped from every run on a user's endpoint.
    final var deployment =
        AnthropicChatOptions.builder().model("claude-haiku-4-5").maxTokens(12345).build();

    final var options =
        AnthropicUserChatClients.optionsFor(
            deployment,
            new AnthropicUserChatClients.Endpoint(null, "sk-ant-x", "claude-sonnet-4-5", null));

    assertThat(options.getMaxTokens()).isEqualTo(12345);
    assertThat(options.getModel()).isEqualTo("claude-sonnet-4-5");
  }
}
