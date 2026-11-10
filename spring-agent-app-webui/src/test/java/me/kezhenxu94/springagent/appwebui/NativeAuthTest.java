package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.security.NativeAuthSecurity;
import me.kezhenxu94.springagent.integration.websocket.security.NativeHandoffCodes;
import me.kezhenxu94.springagent.integration.websocket.security.WebAuthoritiesMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * A native app's way in, through the real filter chain: the PKCE handoff from a browser session,
 * the bearer token it ends in, CSRF not applying to that token, and Sign in with Apple against a
 * key set of the test's own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "spring.ai.openai.base-url=http://localhost:1",
      "spring.ai.openai.api-key=test",
      "spring.ai.openai.chat.model=test-model",
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=test",
      "spring.ai.openai.embedding.model=test-embedding",
      "spring.ai.openai.audio.transcription.base-url=http://localhost:1",
      "spring.ai.openai.audio.transcription.api-key=test",
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-native-auth-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.ai.tools.shell.type=none",
      "app.web.auth.native.token-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "app.web.auth.native.redirect-schemes=springagent",
      "app.web.auth.native.apple-client-ids=com.example.agent",
      "app.web.auth.native.open-registration=false"
    })
class NativeAuthTest {

  static final AppleKeys APPLE = AppleKeys.start();

  @DynamicPropertySource
  static void apple(final DynamicPropertyRegistry registry) {
    registry.add("app.web.auth.native.apple-jwks-uri", APPLE::jwksUri);
  }

  @AfterAll
  static void stop() {
    APPLE.stop();
  }

  private static final String VERIFIER = "verifier-".repeat(6);

  @Autowired MockMvc mvc;
  @Autowired NativeAuthSecurity nativeAuth;
  private final JsonMapper om = JsonMapper.builder().build();

  @Test
  @DisplayName("the sign-in screen is told what to offer before anybody has signed in")
  void config() throws Exception {
    mvc.perform(get("/auth/native/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.browser").value(true))
        .andExpect(jsonPath("$.provider").value("feishu"))
        .andExpect(jsonPath("$.apple").value(true))
        .andExpect(jsonPath("$.title.en").isNotEmpty());
  }

  @Test
  @DisplayName("a browser session becomes a code, the code and verifier a token, the token a user")
  void handoff() throws Exception {
    final var code = authorize("springagent://auth");
    final var token = exchange(code, VERIFIER);

    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.userId").value("ou_1"))
        .andExpect(jsonPath("$.tenantId").value("tenant-under-test"));

    // Spent: a code is good for one exchange, whoever makes it.
    mvc.perform(tokenRequest(code, VERIFIER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
  }

  @Test
  @DisplayName("a code with the wrong verifier is refused, and spent by the attempt")
  void wrongVerifier() throws Exception {
    final var code = authorize("springagent://auth");
    mvc.perform(tokenRequest(code, "another-verifier-".repeat(3)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_grant"));
    mvc.perform(tokenRequest(code, VERIFIER)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("a redirect to a scheme nobody allowed is refused, not followed")
  void disallowedScheme() throws Exception {
    mvc.perform(
            authorizeRequest("https://evil.example/collect")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an anonymous authorize goes through the login and comes back to authorize")
  void anonymousAuthorizeLogsInAndReturns() throws Exception {
    final var session = new MockHttpSession();
    // As a browser asks for a page: the login redirect is offered to a request that accepts HTML,
    // which a sign-in sheet's request does, and a bare 401 to anything else.
    mvc.perform(authorizeRequest("springagent://auth").session(session).accept(MediaType.TEXT_HTML))
        .andExpect(status().isFound())
        .andExpect(
            result ->
                assertThat(result.getResponse().getRedirectedUrl())
                    .endsWith("/oauth2/authorization/feishu"));

    // What the OAuth2 login filter does once the provider sends the browser back.
    final var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/feishu");
    callback.setSession(session);
    final var response = new MockHttpServletResponse();
    nativeAuth.loginSuccessHandler().onAuthenticationSuccess(callback, response, null);
    assertThat(response.getRedirectedUrl()).contains("/auth/native/authorize");

    // And anything else saved still lands on the page, as it always did.
    final var other = new MockHttpSession();
    mvc.perform(get("/files/something").session(other).accept(MediaType.TEXT_HTML))
        .andExpect(status().isFound());
    final var plain = new MockHttpServletRequest("GET", "/login/oauth2/code/feishu");
    plain.setSession(other);
    final var toPage = new MockHttpServletResponse();
    nativeAuth.loginSuccessHandler().onAuthenticationSuccess(plain, toPage, null);
    assertThat(toPage.getRedirectedUrl()).isEqualTo("/");
  }

  @Test
  @DisplayName("a bad bearer token is a 401, never a fall back to the session")
  void badBearer() throws Exception {
    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a POST with a bearer token needs no CSRF token; one with a session still does")
  void csrfOnlyForSessions() throws Exception {
    final var token = exchange(authorize("springagent://auth"), VERIFIER);
    mvc.perform(post("/api/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNotEmpty());
    mvc.perform(
            post("/api/conversations")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("the run stream's websocket handshake accepts a bearer token")
  void websocketHandshake() throws Exception {
    final var token = exchange(authorize("springagent://auth"), VERIFIER);
    // 400 is the handler refusing an upgrade MockMvc cannot make — reached, and so authenticated.
    mvc.perform(get("/ws/runs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an apple identity token issued to another app is refused")
  void appleAudienceMismatch() throws Exception {
    mvc.perform(appleRequest(APPLE.identityToken("com.example.other", "001.apple")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid_token"));
  }

  @Test
  @DisplayName("a valid apple sign-in is refused while open registration is off")
  void appleRegistrationClosed() throws Exception {
    mvc.perform(appleRequest(APPLE.identityToken("com.example.agent", "001.apple")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("registration_closed"))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  private String authorize(final String redirectUri) throws Exception {
    final var location =
        mvc.perform(
                authorizeRequest(redirectUri)
                    .with(
                        SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    final var params = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams();
    assertThat(location).startsWith("springagent://auth");
    assertThat(params.getFirst("state")).isEqualTo("S");
    return params.getFirst("code");
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      authorizeRequest(final String redirectUri) {
    return get("/auth/native/authorize")
        .param("redirect_uri", redirectUri)
        .param("state", "S")
        .param("code_challenge", NativeHandoffCodes.s256(VERIFIER))
        .param("code_challenge_method", "S256");
  }

  private String exchange(final String code, final String verifier) throws Exception {
    final var body =
        mvc.perform(tokenRequest(code, verifier))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(30 * 24 * 3600))
            .andExpect(jsonPath("$.user.userId").value("ou_1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return (String) om.readValue(body, Map.class).get("access_token");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
      final String code, final String verifier) {
    // No CSRF token, on purpose: an app exchanging a code has no session to have one from.
    return post("/auth/native/token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(om.writeValueAsString(Map.of("code", code, "code_verifier", verifier)));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder appleRequest(
      final String identityToken) {
    return post("/auth/native/apple")
        .contentType(MediaType.APPLICATION_JSON)
        .content(om.writeValueAsString(Map.of("identity_token", identityToken, "name", "Ann")));
  }

  static DefaultOAuth2User admitted() {
    return new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE)),
        Map.of("open_id", "ou_1", "name", "Tester", "tenant_key", "tenant-under-test"),
        "name");
  }
}
