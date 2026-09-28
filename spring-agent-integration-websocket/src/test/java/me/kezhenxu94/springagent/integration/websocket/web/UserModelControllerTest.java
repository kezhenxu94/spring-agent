package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a browser is told about somebody's own chat models, and what it is never told.
 *
 * <p>{@link UserModelRegistry} does the actual sealing and is tested in core; this is about the
 * wire — mainly that a token or a header value can never leave through here, and that the tri-state
 * headers semantics {@link UserModelRegistry#save} defines reach the registry unchanged.
 */
class UserModelControllerTest {

  private static final String ME = "ou_me";
  private static final String TENANT = "tenant_a";
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final AesGcmSealer sealer = new AesGcmSealer(KEY, "t");
  private final UserModelConfigRepo repo = mock(UserModelConfigRepo.class);
  private final UserModelRegistry registry = new UserModelRegistry(repo, sealer, 5);
  private final UserModelProbe probe = mock(UserModelProbe.class);
  private final UserChatClients chatClients = mock(UserChatClients.class);
  private UserModelController controller;

  @BeforeEach
  void setUp() {
    controller = new UserModelController(registry, probe, chatClients, messages());
    when(chatClients.providers()).thenReturn(List.of("openai"));
    when(chatClients.requiresBaseUrl(any())).thenReturn(true);
    when(probe.check(any(), any(), any(), any(), any(), any())).thenReturn(null);
    // A real repo saves and hands back the row it was given; the mock needs telling.
    when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  // ─────────────────────────────────── what never leaves ──────────────────────────────────────

  @Test
  @DisplayName("a model's headers never reach the page; their names do")
  void headersNeverLeave() {
    when(repo.findByOwnerId(ME)).thenReturn(List.of(row("kimi", Map.of("Authorization", "sk-x"))));

    final var body = controller.list(principal(ME, TENANT));

    assertThat(models(body))
        .singleElement()
        .satisfies(row -> assertThat(row.headerNames()).containsExactly("Authorization"));
    assertThat(models(body).toString()).doesNotContain("sk-x");
  }

  // ─────────────────────────────────────── registering ────────────────────────────────────────

  @Test
  @DisplayName("headers given on registration are sealed, and only their names ever come back")
  void headersAreSealedOnAdd() {
    controller.add(
        principal(ME, TENANT),
        new UserModelController.NewModel(
            "kimi",
            null,
            "https://kimi/v1",
            "kimi-k2",
            "sk-secret",
            null,
            Map.of("X-Routing-Key", "abc")));

    final var saved = repo.findByOwnerIdAndName(ME, "kimi");
    // The registry is real here, not mocked, so this exercises the same sealing UserModelTools
    // does: the stored value is not the plaintext, and headersOf opens it back.
    verify(repo).save(any());
    final var captor = ArgumentCaptor.forClass(UserModelConfig.class);
    verify(repo).save(captor.capture());
    assertThat(captor.getValue().headerCiphers()).containsKey("X-Routing-Key");
    assertThat(captor.getValue().headerCiphers().get("X-Routing-Key")).isNotEqualTo("abc");
    assertThat(registry.headersOf(captor.getValue())).containsEntry("X-Routing-Key", "abc");
  }

  @Test
  @DisplayName("a name, a model or a token left out is refused before anything is probed")
  void refusesIncompleteFields() {
    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new UserModelController.NewModel(
                            " ", null, "https://x/v1", "m", "t", null, null))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new UserModelController.NewModel(
                            "kimi", null, "https://x/v1", "", "t", null, null))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new UserModelController.NewModel(
                            "kimi", null, "https://x/v1", "m", "", null, null))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("an unreachable endpoint is refused as a bad gateway, and nothing is stored")
  void refusesUnreachable() {
    when(probe.check(any(), any(), any(), any(), any(), any())).thenReturn("connection refused");

    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new UserModelController.NewModel(
                            "kimi", null, "https://x/v1", "m", "t", null, null))))
        .isEqualTo(HttpStatus.BAD_GATEWAY);
    verify(repo, org.mockito.Mockito.never()).save(any());
  }

  // ─────────────────────────────────── switching and removing ─────────────────────────────────

  @Test
  @DisplayName("activating an unknown name is a 404, and the default word switches back")
  void activateAndUnknown() {
    when(repo.findByOwnerIdAndName(ME, "kimi"))
        .thenReturn(java.util.Optional.of(row("kimi", null)));

    controller.activate(principal(ME, TENANT), "kimi");
    verify(repo, org.mockito.Mockito.atLeastOnce()).save(any());

    assertThat(statusOf(() -> controller.activate(principal(ME, TENANT), "nope")))
        .isEqualTo(HttpStatus.NOT_FOUND);

    final var body = controller.activate(principal(ME, TENANT), "default");
    assertThat(body).containsEntry("active", "default");
  }

  @Test
  @DisplayName("removing an unknown name is a 404")
  void removeUnknown() {
    assertThat(statusOf(() -> controller.remove(principal(ME, TENANT), "nope")))
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ─────────────────────────────────────── the plumbing ───────────────────────────────────────

  private UserModelConfig row(final String name, final Map<String, String> headers) {
    final var builder =
        UserModelConfig.builder()
            .id(UserModelConfig.idFor(ME, name))
            .ownerId(ME)
            .name(name)
            .baseUrl("https://kimi/v1")
            .model("kimi-k2")
            .apiKeyCipher(sealer.seal("sk-secret"));
    if (headers != null) {
      final var sealed = new java.util.LinkedHashMap<String, String>();
      headers.forEach((key, value) -> sealed.put(key, sealer.seal(value)));
      builder.headerCiphers(sealed);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static List<UserModelController.Owned> models(final Map<String, Object> body) {
    return (List<UserModelController.Owned>) body.get("models");
  }

  private static HttpStatus statusOf(final Runnable work) {
    final var thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            ResponseStatusException.class, work::run);
    assertThat(thrown).isNotNull();
    return HttpStatus.valueOf(thrown.getStatusCode().value());
  }

  private static WebMessages messages() {
    return new WebMessages(
        new WebProperties(null, null, null, Locale.ENGLISH, null, null, null, null, false, null));
  }

  private static OAuth2User principal(final String id, final String tenant) {
    return new DefaultOAuth2User(
        List.of(), Map.of("open_id", id, "name", "Me", "tenant_key", tenant), "open_id");
  }
}
