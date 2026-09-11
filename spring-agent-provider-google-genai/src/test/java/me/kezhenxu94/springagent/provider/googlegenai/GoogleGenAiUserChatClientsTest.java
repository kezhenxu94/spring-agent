package me.kezhenxu94.springagent.provider.googlegenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * What a row registered against Gemini is actually reached with.
 *
 * <p>The case worth pinning is a Gemini row on a deployment that serves chat from somebody else:
 * Gemini's Developer API has one well-known host, so such a row carries a key and a model and
 * <em>no base URL at all</em>. Read as "the application's own endpoint" — which is what a blank
 * base URL meant while a deployment had one provider — it would borrow a Gemini credential this
 * deployment does not have, ignoring the key the person typed. The probe would pass, being handed
 * the token directly, and every run would fail.
 */
class GoogleGenAiUserChatClientsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final AesGcmSealer sealer = new AesGcmSealer(KEY, "t");

  private final UserModelRegistry registry =
      new UserModelRegistry(mock(UserModelConfigRepo.class), sealer, 3);

  /** A deployment with no Gemini of its own: no key, no model. */
  private GoogleGenAiUserChatClients withoutADeploymentGemini() {
    return new GoogleGenAiUserChatClients(
        registry,
        GoogleGenAiChatOptions.builder().build(),
        null,
        ToolCallingManager.builder().build(),
        4);
  }

  private UserModelConfig row(final String baseUrl) {
    return UserModelConfig.builder()
        .id(UserModelConfig.idFor("u1", "mine"))
        .ownerId("u1")
        .name("mine")
        .provider(GoogleGenAiUserChatClients.PROVIDER)
        .baseUrl(baseUrl)
        .model("gemini-2.5-pro")
        .apiKeyCipher(sealer.seal("AIza-the-users-own"))
        .activated(true)
        .build();
  }

  @Test
  @DisplayName("no base URL is needed, because there is nothing to type")
  void noBaseUrlIsNeeded() {
    assertThat(withoutADeploymentGemini().requiresBaseUrl()).isFalse();
  }

  @Test
  @DisplayName("a row with no base URL still uses the key the person typed")
  void aRowWithoutABaseUrlKeepsItsOwnKey() {
    // The bug this exists for: borrowing used to key on the base URL being blank, so this row
    // authenticated with the deployment's Gemini credential — absent here — instead of its own.
    final var endpoint = GoogleGenAiUserChatClients.endpointFor(registry, null, "", row(null));

    assertThat(endpoint.apiKey()).isEqualTo("AIza-the-users-own");
    // Blank means the Developer API's own host, which is what a Gemini row normally wants.
    assertThat(endpoint.baseUrl()).isNull();
    assertThat(endpoint.model()).isEqualTo("gemini-2.5-pro");
  }

  @Test
  @DisplayName("a base URL is honoured where one is given, for a gateway re-serving the protocol")
  void aBaseUrlIsHonouredWhenGiven() {
    final var endpoint =
        GoogleGenAiUserChatClients.endpointFor(
            registry, null, "", row("https://gemini.gateway.example.com"));

    assertThat(endpoint.baseUrl()).isEqualTo("https://gemini.gateway.example.com");
    assertThat(endpoint.apiKey()).isEqualTo("AIza-the-users-own");
  }

  @Test
  @DisplayName("a row carrying no credential borrows the deployment's, which is what it stands for")
  void aRowWithNoCredentialBorrows() {
    // UserModelRegistry.DEFAULT_ROW: the application's own model with an effort of the user's
    // choosing. Nothing to authenticate with of its own, so the deployment's key is correct here.
    final var defaultRow =
        UserModelConfig.builder()
            .name(UserModelRegistry.DEFAULT_ROW)
            .reasoningEffort("low")
            .build();

    final var endpoint =
        GoogleGenAiUserChatClients.endpointFor(
            registry,
            GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build(),
            "AIza-the-deployments",
            defaultRow);

    assertThat(endpoint.apiKey()).isEqualTo("AIza-the-deployments");
    assertThat(endpoint.model()).isEqualTo("gemini-2.5-flash");
  }
}
