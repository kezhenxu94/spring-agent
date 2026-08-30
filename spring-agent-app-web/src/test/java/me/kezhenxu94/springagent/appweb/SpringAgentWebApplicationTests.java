package me.kezhenxu94.springagent.appweb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import me.kezhenxu94.springagent.appweb.security.WebAuthoritiesMapper;
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

/**
 * That it starts, that it starts as this application rather than the server, and that the rules
 * which keep other people out are the ones actually wired.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      // Nothing here calls a model; these only have to resolve, since application.yaml declares
      // them as placeholders with no defaults.
      "spring.ai.openai.base-url=http://localhost:1",
      "spring.ai.openai.api-key=test",
      "spring.ai.openai.chat.model=test-model",
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=test",
      "spring.ai.openai.embedding.model=test-embedding",
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      // A database of its own per run, rather than the developer's real one under ~/.spring-agent.
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.ai.tools.shell.type=none"
    })
class SpringAgentWebApplicationTests {

  @Autowired ApplicationContext context;
  @Autowired MockMvc mvc;
  @Autowired WebAuthoritiesMapper authoritiesMapper;

  @Test
  void carriesNoIntegration() throws Exception {
    // The module depends on none of them, and this notices one arriving transitively — which is how
    // a "plain agent web server" quietly grows a bot.
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(
            name -> {
              final var lower = name.toLowerCase();
              return lower.contains("feishu")
                  || lower.contains("github")
                  || lower.contains("gitlab")
                  || lower.contains("grafana")
                  || lower.contains("webhook");
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
