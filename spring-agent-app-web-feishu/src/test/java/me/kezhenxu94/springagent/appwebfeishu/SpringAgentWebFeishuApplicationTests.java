package me.kezhenxu94.springagent.appwebfeishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.security.WebAuthoritiesMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * That it starts with both surfaces, and that the rules which keep other people out are the ones
 * actually wired.
 *
 * <p>The same file as {@code spring-agent-app-webui}'s, with one assertion inverted: that module
 * asserts no chat integration has arrived transitively, and this one asserts Feishu has, since that
 * is the whole difference between them. Whether two surfaces coexist correctly is a separate
 * question, and {@code OneChatSurfacePlusWebTest} is where it is asked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = WebFeishuTestProperties.ALL)
class SpringAgentWebFeishuApplicationTests {

  /** Not started, so no long connection to Feishu is opened by a test. */
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.lark.oapi.ws.Client feishuClient;

  @Autowired ApplicationContext context;
  @Autowired MockMvc mvc;
  @Autowired WebAuthoritiesMapper authoritiesMapper;

  @Test
  void carriesFeishuAndNothingElse() {
    // Feishu is here on purpose. Asserted rather than assumed, because everything this application
    // exists for is silently absent if the module failed to be registered — the auto-configuration
    // is @ConditionalOnProperty, so app.feishu.enabled=false leaves a build that passes and a
    // deployment that is just spring-agent-app-webui under another name.
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("feishu"))
        .isNotEmpty();

    // Nothing else, though, and this notices one arriving transitively.
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(
            name -> {
              final var lower = name.toLowerCase();
              return lower.contains("github")
                  || lower.contains("gitlab")
                  || lower.contains("grafana")
                  || lower.contains("slack");
            })
        .isEmpty();
  }

  @Test
  void anApiCallWithoutASessionIsRefusedRatherThanRedirected() throws Exception {
    // A redirect would be followed by fetch() and the page would parse a login form as its answer.
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void thePageItselfIsReadableBeforeLoggingIn() throws Exception {
    mvc.perform(get("/index.html")).andExpect(status().isOk());
  }

  @Test
  void aPostWithoutACsrfTokenIsRefused() throws Exception {
    // The reason CSRF is on here and off in spring-agent-app: this POST makes the agent act as
    // whoever is logged in, so a request another site could forge is a request another site could
    // put words in their mouth with.
    mvc.perform(
            post("/api/conversations")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
        .andExpect(status().isForbidden());
  }

  @Test
  void aLoginFromAnotherTenantDoesNotGetTheRole() {
    final var mine =
        authoritiesMapper.mapAuthorities(
            java.util.List.of(
                new OAuth2UserAuthority(
                    Map.of("tenant_key", "tenant-under-test", "open_id", "ou_1"))));
    // contains, not containsExactly: the OAuth2UserAuthority and every SCOPE_* the token carries
    // are passed through rather than replaced, since they say what the token actually permits.
    assertThat(mine).extracting(GrantedAuthority::getAuthority).contains(WebAuthoritiesMapper.ROLE);

    final var theirs =
        authoritiesMapper.mapAuthorities(
            java.util.List.of(
                new OAuth2UserAuthority(Map.of("tenant_key", "somebody-else", "open_id", "ou_2"))));
    assertThat(theirs)
        .extracting(GrantedAuthority::getAuthority)
        .doesNotContain(WebAuthoritiesMapper.ROLE);
  }

  @Test
  @DisplayName("a refused person can still ask who they are, and is told they are refused")
  void arefusedPersonIsToldWhyRatherThanStonewalled() throws Exception {
    // The bug this exists for: every call answering 403 with no body is indistinguishable from the
    // server being broken, so a misconfigured tenant id looked like an outage. /api/me is the one
    // endpoint reachable without the role, and it reports the verdict rather than enforcing it.
    final var body =
        mvc.perform(
                get("/api/me")
                    .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(refused())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("\"allowed\":false");

    // Everything the verdict protects is still refused.
    mvc.perform(
            get("/api/conversations")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(refused())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("somebody from the right tenant is allowed")
  void therightTenantIsAllowed() throws Exception {
    final var body =
        mvc.perform(
                get("/api/me")
                    .with(
                        SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("\"allowed\":true");
  }

  @Test
  @DisplayName("the first page load already carries the CSRF cookie the page has to echo")
  void theCsrfCookieIsSetBeforeAnythingIsPosted() throws Exception {
    // The bug this exists for: with deferred CSRF tokens nothing on a freshly loaded page resolves
    // the token, so no cookie is written, so the *first* POST of a session is refused and only that
    // refusal sets the cookie — "reload and try again" as a user-visible behaviour. Asserted on the
    // page itself because that is the first request a browser makes.
    mvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(MockMvcResultMatchers.cookie().exists("XSRF-TOKEN"));
  }

  @Test
  @DisplayName("the run stream's websocket is mapped, and behind the role like everything else")
  void theWebsocketEndpointIsMappedAndGuarded() throws Exception {
    // Two assertions in one request each, and both matter. That it is *mapped* is what a rename of
    // the endpoint or a broker configuration that never ran would break, and the page would fail
    // to stream with nothing in the log to say why. That it is *guarded* is the whole of the
    // websocket's authentication: the handshake is an ordinary GET through the filter chain, and
    // the STOMP session takes its principal from it.
    mvc.perform(get("/ws/runs")).andExpect(status().isUnauthorized());

    // Reached the handler, which refuses it because MockMvc cannot upgrade a connection. Anything
    // but 400 here — a 404, a redirect — means the endpoint is not where the page looks for it.
    mvc.perform(
            get("/ws/runs")
                .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(admitted())))
        .andExpect(status().isBadRequest());
  }

  /** Somebody this deployment serves. */
  private static DefaultOAuth2User admitted() {
    return userOf("tenant-under-test", true);
  }

  /** Somebody signed in whom this deployment does not serve. */
  private static DefaultOAuth2User refused() {
    return userOf("somebody-else", false);
  }

  /**
   * A signed-in person, with the authorities they would be carrying by the time a request reaches a
   * controller.
   *
   * <p>{@code admitted} is passed rather than derived, because this post-processor installs the
   * authentication ready-made and never runs {@link WebAuthoritiesMapper} — the mapper belongs to
   * the login flow. Deriving it here would mean these tests silently asserting nothing about the
   * role, which is how a CSRF test comes to pass because the caller lacked the role instead. {@link
   * #aLoginFromAnotherTenantDoesNotGetTheRole} is what covers the mapper itself.
   */
  private static DefaultOAuth2User userOf(final String tenant, final boolean admitted) {
    final var attributes =
        Map.<String, Object>of("open_id", "ou_1", "name", "Tester", "tenant_key", tenant);
    final var authorities =
        new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
    authorities.add(new OAuth2UserAuthority(attributes));
    if (admitted) {
      authorities.add(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE));
    }
    return new DefaultOAuth2User(authorities, attributes, "name");
  }
}
