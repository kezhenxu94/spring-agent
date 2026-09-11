package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Choosing a protocol per row, which is what lets a person bring a model rather than only a URL.
 *
 * <p>Before this, a deployment published one {@code UserChatClients} — whichever module built the
 * chat model — and every registered endpoint was spoken to with that module's SDK whatever the user
 * typed. The row had no provider to record and the form had nothing to ask.
 */
class DispatchingUserChatClientsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final ChatClient applicationClient = mock(ChatClient.class);
  private final ChatClient openAiClient = mock(ChatClient.class);
  private final ChatClient geminiClient = mock(ChatClient.class);

  /** A provider that answers with one identifiable client, and records nothing else. */
  private record StubProvider(
      String provider, ChatClient client, String effort, boolean requiresBaseUrl)
      implements ProviderChatClients {
    @Override
    public ChatClient clientFor(final UserModelConfig config) {
      return client;
    }

    @Override
    public ChatClient probeClient(final UserModelConfig config, final String token) {
      return client;
    }

    @Override
    public String configuredEffort() {
      return effort;
    }
  }

  private final ProviderChatClients openai =
      new StubProvider("openai", openAiClient, "xhigh", true);
  private final ProviderChatClients gemini =
      new StubProvider("google-genai", geminiClient, "high", false);

  private UserModelRegistry registryWith(final UserModelConfig... rows) {
    final var repo = mock(UserModelConfigRepo.class);
    if (rows.length > 0) {
      when(repo.findByOwnerId("u1")).thenReturn(List.of(rows));
    }
    return new UserModelRegistry(repo, new AesGcmSealer(KEY, "t"), 3);
  }

  private static UserModelConfig row(final String provider, final String effort) {
    return UserModelConfig.builder()
        .id(UserModelConfig.idFor("u1", "mine"))
        .ownerId("u1")
        .name("mine")
        .provider(provider)
        .baseUrl("https://own/v1")
        .model("own-model")
        .reasoningEffort(effort)
        .activated(true)
        .build();
  }

  private DispatchingUserChatClients dispatching(
      final UserModelRegistry registry, final String configured) {
    return new DispatchingUserChatClients(
        applicationClient, registry, List.of(openai, gemini), configured);
  }

  @Test
  @DisplayName("a row naming a provider is spoken in that provider's protocol")
  void aRowChoosesItsProtocol() {
    // The feature itself: the deployment runs Gemini, the user registered an OpenAI endpoint, and
    // their key goes out over the OpenAI protocol rather than generateContent.
    final var clients = dispatching(registryWith(row("openai", null)), "google-genai");

    assertThat(clients.forUser("u1")).isSameAs(openAiClient);
  }

  @Test
  @DisplayName("a row naming none is spoken in whatever the deployment speaks")
  void aRowNamingNoneFollowsTheDeployment() {
    // Every row written before the field existed, and every user who never opened the select.
    assertThat(dispatching(registryWith(row(null, null)), "google-genai").forUser("u1"))
        .isSameAs(geminiClient);
    assertThat(dispatching(registryWith(row(null, null)), "openai").forUser("u1"))
        .isSameAs(openAiClient);
  }

  @Test
  @DisplayName("the deployment's provider comes from configuration, not from bean order")
  void theDefaultIsNotBeanOrder() {
    // openai is first in the list both times; only spring.ai.model.chat decides. Bean order says
    // nothing here, because a provider publishes its clients even when it built no chat model.
    assertThat(dispatching(registryWith(row(null, null)), "google-genai").defaultProvider())
        .isEqualTo("google-genai");
  }

  @Test
  @DisplayName("only the protocols actually on the classpath are offered")
  void onlyPublishedProvidersAreOffered() {
    // What a form draws its select from. A protocol nobody implements must never be offered:
    // registering against it would create a row that can only ever fail.
    assertThat(dispatching(registryWith(), "openai").providers())
        .containsExactly("openai", "google-genai");

    final var oneProvider =
        new DispatchingUserChatClients(
            applicationClient, registryWith(), List.of(gemini), "openai");
    assertThat(oneProvider.providers()).containsExactly("google-genai");
    // spring.ai.model.chat names a provider that serves no user models here, so the one that does
    // is what a row naming none gets — rather than nothing at all.
    assertThat(oneProvider.defaultProvider()).isEqualTo("google-genai");
  }

  @Test
  @DisplayName("a row naming a protocol nobody serves falls back, and does not fail the run")
  void anUnservedProtocolFallsBack() {
    // A provider module dropped from a deployment that had rows against it. The run still happens,
    // on the application's own model, because failing here would fail the run used to fix it.
    final var clients = dispatching(registryWith(row("anthropic", null)), "openai");

    assertThat(clients.forUser("u1")).isSameAs(applicationClient);
  }

  @Test
  @DisplayName("a probe against a protocol nobody serves says so rather than testing another")
  void anUnservedProtocolFailsAProbe() {
    // The one path that does not fall back: a probe exists to tell somebody whether what they typed
    // works, and quietly testing a different protocol would answer a question they did not ask.
    final var clients = dispatching(registryWith(), "openai");

    assertThatThrownBy(() -> clients.probeClient(row("anthropic", null), "sk-test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("anthropic")
        .hasMessageContaining("openai");
  }

  @Test
  @DisplayName("no user at all, and no row, is the application's own client")
  void noRowIsTheApplicationsOwn() {
    assertThat(dispatching(registryWith(), "openai").forUser("u1")).isSameAs(applicationClient);
    assertThat(dispatching(registryWith(), "openai").forUser(null)).isSameAs(applicationClient);
  }

  @Test
  @DisplayName("the effort reported is the serving provider's, not the deployment's")
  void effortFollowsTheServingProvider() {
    // A user on an OpenAI row, on a Gemini deployment, with no effort of their own: the honest
    // answer is what OpenAI is configured with here, not what Gemini is.
    assertThat(dispatching(registryWith(row("openai", null)), "google-genai").effortInForce("u1"))
        .isEqualTo("xhigh");
    // And a user who chose one is reported with theirs whatever serves them.
    assertThat(dispatching(registryWith(row("openai", "low")), "google-genai").effortInForce("u1"))
        .isEqualTo("low");
    // not-sent is a third state: no effort at all, not the default.
    assertThat(
            dispatching(registryWith(row("openai", ReasoningEfforts.NOT_SENT)), "google-genai")
                .effortInForce("u1"))
        .isNull();
  }

  @Test
  @DisplayName("an unreadable registry costs a label and a client, never a run")
  void neverThrows() {
    final var broken = mock(UserModelConfigRepo.class);
    when(broken.findByOwnerId("u1")).thenThrow(new IllegalStateException("database is on fire"));
    final var clients =
        dispatching(new UserModelRegistry(broken, new AesGcmSealer(KEY, "t"), 3), "openai");

    assertThat(clients.forUser("u1")).isSameAs(applicationClient);
    assertThat(clients.effortInForce("u1")).isEqualTo("xhigh");
  }

  @Test
  @DisplayName("with no provider module at all, everything falls back rather than exploding")
  void noProvidersAtAll() {
    final var clients =
        new DispatchingUserChatClients(
            applicationClient, registryWith(row(null, null)), List.of(), "openai");

    assertThat(clients.providers()).isEmpty();
    assertThat(clients.defaultProvider()).isNull();
    assertThat(clients.forUser("u1")).isSameAs(applicationClient);
    assertThat(clients.effortInForce("u1")).isNull();
  }

  @Test
  @DisplayName("whether a base URL is needed is the chosen provider's answer, not a fixed rule")
  void baseUrlIsAskedOfTheProvider() {
    // Gemini's Developer API has one well-known host, so an ordinary Gemini row is a key and a
    // model and no URL. Requiring one regardless would refuse a registration for want of a field
    // with nothing to put in it.
    final var clients = dispatching(registryWith(), "openai");

    assertThat(clients.requiresBaseUrl("google-genai")).isFalse();
    assertThat(clients.requiresBaseUrl("openai")).isTrue();
    // Naming none asks the deployment's own.
    assertThat(clients.requiresBaseUrl(null)).isTrue();
    // A protocol nobody serves: requiring one is the safer answer about an endpoint that cannot be
    // reached here anyway.
    assertThat(clients.requiresBaseUrl("anthropic")).isTrue();
  }
}
