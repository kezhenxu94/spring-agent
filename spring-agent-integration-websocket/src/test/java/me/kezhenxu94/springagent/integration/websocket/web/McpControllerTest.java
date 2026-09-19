package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.tools.mcp.McpConfiguredServer;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistration;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistryException;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistryException.Reason;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerRegistry;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerSpec;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a browser is told about somebody's MCP servers, and what it is never told.
 *
 * <p>The registry's own decisions are tested in core; this is about the wire. Two things are worth
 * more than the rest of the file: that a header value can never leave through here, and that a save
 * which does not mention the headers keeps the ones already stored — without the second, opening a
 * server to correct its URL would quietly drop its authentication and the next run would get a 401
 * from a server that had been working.
 */
class McpControllerTest {

  private static final String ME = "ou_me";
  private static final String TENANT = "tenant_a";

  private final McpServerRegistry registry = mock(McpServerRegistry.class);
  private McpController controller;

  @BeforeEach
  void setUp() {
    controller = new McpController(registry, messages());
    when(registry.owned(any())).thenReturn(List.of());
    when(registry.sharedWith(any(), any())).thenReturn(List.of());
    when(registry.applicationConfigured()).thenReturn(List.of());
  }

  // ─────────────────────────────────── what never leaves ──────────────────────────────────────

  @Test
  @DisplayName("a server's headers never reach the page; their names do")
  void headersNeverLeave() {
    when(registry.owned(ME))
        .thenReturn(
            List.of(
                server("github", "https://mcp.example/x")
                    .headers(
                        new LinkedHashMap<>(
                            Map.of("Authorization", "Bearer sk-secret", "X-Org", "acme")))
                    .build()));

    final var owned = owned(controller.list(principal(ME, TENANT)));

    assertThat(owned)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.headerNames()).containsExactly("Authorization", "X-Org");
              assertThat(row.url()).isEqualTo("https://mcp.example/x");
            });
    // The whole response, serialized shape included, must not carry the value anywhere.
    assertThat(owned.toString()).doesNotContain("sk-secret");
  }

  @Test
  @DisplayName("a server somebody shared carries its name and owner, and no connection detail")
  void sharedCarriesNoConnection() {
    when(registry.sharedWith(ME, null))
        .thenReturn(
            List.of(
                server("theirs", "https://private.example/mcp")
                    .ownerId("ou_them")
                    .headers(new LinkedHashMap<>(Map.of("Authorization", "Bearer sk-theirs")))
                    .build()));

    final var shared = shared(controller.list(principal(ME, TENANT)));

    assertThat(shared)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.name()).isEqualTo("theirs");
              assertThat(row.owner()).isEqualTo("ou_them");
            });
    assertThat(shared.toString()).doesNotContain("private.example").doesNotContain("sk-theirs");
  }

  @Test
  @DisplayName("a browser session is in no chat, so it is offered no chat's shares")
  void noChatShares() {
    controller.list(principal(ME, TENANT));

    // Null and not the user id: passing anything else here would hand this session servers shared
    // with a Feishu group, which is an access the session does not have.
    verify(registry).sharedWith(ME, null);
  }

  @Test
  @DisplayName("what this deployment configures is listed, as something nobody owns")
  void listsApplicationConfigured() {
    when(registry.applicationConfigured())
        .thenReturn(List.of(new McpConfiguredServer("company", "https://mcp.corp/mcp")));

    assertThat(configured(controller.list(principal(ME, TENANT))))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.name()).isEqualTo("company");
              assertThat(row.url()).isEqualTo("https://mcp.corp/mcp");
            });
  }

  @Test
  @DisplayName("the prefix reported is the one the tools actually wear, hash and all")
  void reportsTheEffectivePrefix() {
    when(registry.owned(ME))
        .thenReturn(
            List.of(
                server("chosen", "https://a.example/mcp").toolPrefix("gh").build(),
                server("derived", "https://b.example/mcp").build()));

    final var owned = owned(controller.list(principal(ME, TENANT)));

    assertThat(owned.get(0).toolPrefix()).isEqualTo("gh");
    assertThat(owned.get(0).toolPrefixChosen()).isTrue();
    // Nobody chose one, so it is the hash McpClientFactory derives — reported because it is what
    // the person will see in front of every tool name, and nothing else would ever tell them.
    assertThat(owned.get(1).toolPrefix()).isNotBlank();
    assertThat(owned.get(1).toolPrefixChosen()).isFalse();
  }

  // ─────────────────────────────────────── registering ────────────────────────────────────────

  @Test
  @DisplayName("a save that does not mention the headers keeps the ones already stored")
  void keepsStoredHeaders() {
    final var stored =
        server("github", "https://old.example/mcp")
            .headers(new LinkedHashMap<>(Map.of("Authorization", "Bearer sk-kept")))
            .build();
    when(registry.find(ME, "github")).thenReturn(stored);
    when(registry.register(any(), any(), any(), any()))
        .thenReturn(new McpRegistration(stored, List.of("search")));

    controller.add(
        principal(ME, TENANT),
        new McpController.NewServer(
            "github", "https://new.example/mcp", null, null, null, null, null, null));

    assertThat(capturedSpec().headers()).containsEntry("Authorization", "Bearer sk-kept");
  }

  @Test
  @DisplayName("an empty headers object is how the page clears them")
  void clearsHeadersWhenAskedTo() {
    when(registry.register(any(), any(), any(), any()))
        .thenReturn(
            new McpRegistration(server("github", "https://x.example/mcp").build(), List.of()));

    controller.add(
        principal(ME, TENANT),
        new McpController.NewServer(
            "github", "https://x.example/mcp", Map.of(), null, null, null, null, null));

    assertThat(capturedSpec().headers()).isEmpty();
    // Nothing was looked up: an explicit answer about the headers is the whole answer.
    verify(registry, never()).find(any(), any());
  }

  @Test
  @DisplayName("the tool names the probe found come back with the registration, and are not stored")
  void reportsWhatTheProbeFound() {
    when(registry.register(any(), any(), any(), any()))
        .thenReturn(
            new McpRegistration(
                server("github", "https://x.example/mcp").build(),
                List.of("search_issues", "create_pr")));

    final var body =
        controller
            .add(
                principal(ME, TENANT),
                new McpController.NewServer(
                    "github", "https://x.example/mcp", null, null, null, null, null, null))
            .getBody();

    assertThat(body).containsEntry("tools", List.of("search_issues", "create_pr"));
    assertThat(body.get("server")).isInstanceOf(McpController.Owned.class);
  }

  @Test
  @DisplayName("a name or a URL that was left out is refused before anything is probed")
  void refusesEmptyFields() {
    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new McpController.NewServer(
                            " ", "https://x.example/mcp", null, null, null, null, null, null))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            statusOf(
                () ->
                    controller.add(
                        principal(ME, TENANT),
                        new McpController.NewServer(
                            "github", "", null, null, null, null, null, null))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    verify(registry, never()).register(any(), any(), any(), any());
  }

  // ─────────────────────────────────── what a refusal becomes ─────────────────────────────────

  /**
   * Each reason becomes the status that says what the person can do next.
   *
   * <p>The split is deliberate and is the reason the registry throws a reason rather than a
   * sentence: a URL this runtime will not take and a server that did not answer look alike and are
   * not, and a page that reported the second as a bad request would have somebody editing a URL
   * that was right all along.
   */
  @Nested
  @DisplayName("a registry refusal")
  class Refusals {

    @Test
    @DisplayName("about the request itself is a 400")
    void badRequest() {
      assertThat(whenRegisterThrows(Reason.INVALID, "loopback addresses are not allowed"))
          .isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(whenRegisterThrows(Reason.PREFIX_TAKEN, "gh", "other"))
          .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("about the server not answering is a 502, because nothing here was wrong")
    void badGateway() {
      assertThat(whenRegisterThrows(Reason.UNREACHABLE, "github", "connection refused"))
          .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("about a name that is not the caller's is a 404")
    void notFound() {
      when(registry.setEnabled(any(), any(), anyBool()))
          .thenThrow(new McpRegistryException(Reason.UNKNOWN, "github"));

      assertThat(
              statusOf(
                  () ->
                      controller.enabled(
                          principal(ME, TENANT), new McpController.Enabled("github", false))))
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("about a name this deployment configures is a 409, not a 404")
    void conflict() {
      // It exists and its tools are already reachable; saying "no such server" would have somebody
      // registering a second one under the same name.
      assertThat(whenRegisterThrows(Reason.APPLICATION_CONFIGURED, "company"))
          .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("about a share that is already there is a 409")
    void alreadyShared() {
      doThrowOnShare(new McpRegistryException(Reason.ALREADY_SHARED, "github", "ou_them"));

      assertThat(
              statusOf(
                  () ->
                      controller.share(
                          principal(ME, TENANT), new McpController.Share("github", "ou_them"))))
          .isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * {@code doThrow} and not {@code when(...).thenThrow(...)}: the second form calls the method to
     * work out what is being stubbed, and calling one that is already stubbed to throw throws there
     * — so a test that re-stubs the same method twice fails inside its own setup.
     */
    private HttpStatus whenRegisterThrows(final Reason reason, final Object... arguments) {
      org.mockito.Mockito.doThrow(new McpRegistryException(reason, arguments))
          .when(registry)
          .register(any(), any(), any(), any());
      return statusOf(
          () ->
              controller.add(
                  principal(ME, TENANT),
                  new McpController.NewServer(
                      "github", "https://x.example/mcp", Map.of(), null, null, null, null, null)));
    }
  }

  // ─────────────────────────────────────── sharing ────────────────────────────────────────────

  @Test
  @DisplayName("sharing and revoking name the caller as the owner, never the request")
  void ownershipIsThePrincipals() {
    controller.share(principal(ME, TENANT), new McpController.Share("github", "ou_them"));
    verify(registry).share(ME, "github", "ou_them");

    controller.unshare(principal(ME, TENANT), "github", "ou_them");
    verify(registry).unshare(ME, "github", "ou_them");
  }

  @Test
  @DisplayName("a share with no target is refused rather than stored blank")
  void refusesBlankTarget() {
    assertThat(
            statusOf(
                () ->
                    controller.share(
                        principal(ME, TENANT), new McpController.Share("github", "  "))))
        .isEqualTo(HttpStatus.BAD_REQUEST);
    verify(registry, never()).share(any(), any(), any());
  }

  @Test
  @DisplayName("removing names the caller as the owner")
  void removesAsTheCaller() {
    controller.remove(principal(ME, TENANT), "github");
    verify(registry).remove(ME, "github");
  }

  // ─────────────────────────────────────── the plumbing ───────────────────────────────────────

  private static boolean anyBool() {
    return org.mockito.ArgumentMatchers.anyBoolean();
  }

  private void doThrowOnShare(final RuntimeException thrown) {
    org.mockito.Mockito.doThrow(thrown).when(registry).share(any(), any(), any());
  }

  private McpServerSpec capturedSpec() {
    final var spec = ArgumentCaptor.forClass(McpServerSpec.class);
    verify(registry).register(eq(ME), eq(null), spec.capture(), any());
    return spec.getValue();
  }

  private static HttpStatus statusOf(final Runnable work) {
    final var thrown =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            ResponseStatusException.class, work::run);
    assertThat(thrown).isNotNull();
    return HttpStatus.valueOf(thrown.getStatusCode().value());
  }

  private static McpServerConfig.McpServerConfigBuilder server(
      final String name, final String url) {
    return McpServerConfig.builder()
        .id(name)
        .ownerId(ME)
        .name(name)
        .transport(McpServerConfig.Transport.STREAMABLE_HTTP)
        .url(url)
        .enabled(true);
  }

  @SuppressWarnings("unchecked")
  private static List<McpController.Owned> owned(final Map<String, Object> body) {
    return (List<McpController.Owned>) body.get("owned");
  }

  @SuppressWarnings("unchecked")
  private static List<McpController.Shared> shared(final Map<String, Object> body) {
    return (List<McpController.Shared>) body.get("shared");
  }

  @SuppressWarnings("unchecked")
  private static List<McpController.Configured> configured(final Map<String, Object> body) {
    return (List<McpController.Configured>) body.get("configured");
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
