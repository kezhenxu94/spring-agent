package me.kezhenxu94.springagent.appwebui;

import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.websocket.security.WebAuthoritiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Who may reach what.
 *
 * <p><b>CSRF is on here, where {@code spring-agent-app-feishu}'s {@code SecurityConfigurer} turns
 * it off.</b> That is not an inconsistency to tidy up. The server's POST endpoints are webhooks:
 * they carry no cookie, cannot log in, and verify a signature over their own body, so a cross-site
 * request forgery has nothing to forge with. This application is the opposite case — a browser with
 * a session cookie, and a POST that makes an agent act with the logged-in person's credentials,
 * files and MCP servers. With CSRF off, any page that person visited could send messages as them.
 *
 * <p>{@code withHttpOnlyFalse} because the page is plain JavaScript with no template engine to
 * stamp a token into: it reads the {@code XSRF-TOKEN} cookie and echoes it back in {@code
 * X-XSRF-TOKEN}. That is the standard arrangement and is not the weakening it looks like — the
 * protection comes from the attacker's inability to read the cookie across origins, which the
 * same-origin policy provides whether or not the cookie is HttpOnly.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfigurer {

  /**
   * The OAuth2 registration id the sign-in page redirects to. Not final: {@code @Value} on a field
   * is an injection point in its own right, and AOT generates a plain field assignment for it,
   * which cannot target a final field the way reflective injection can.
   */
  @org.springframework.beans.factory.annotation.Value("${app.web.auth.provider:feishu}")
  String loginProvider;

  private final WebAuthoritiesMapper authoritiesMapper;

  @Bean
  SecurityFilterChain filterChain(final HttpSecurity http) throws Exception {
    final var csrfHandler = new CsrfTokenRequestAttributeHandler();
    // Opts out of deferred CSRF tokens, and this is a fix rather than a tuning knob. Since Spring
    // Security 6 the token is resolved lazily — only when something actually reads it — and a
    // repository that has not been read has written no cookie. Nothing on a freshly loaded page
    // reads it: the page is a static file and /api/me is a GET. So the first POST of a session went
    // out with no X-XSRF-TOKEN header, was refused, and only the refusal itself resolved the token
    // and set the cookie — which is why the page worked on the second try and after a reload.
    //
    // A null attribute name makes the handler ask the token for its parameter name on every
    // request, which resolves it, which saves the cookie. The alternative in Spring Security's own
    // SPA guidance is a filter that touches the token for the same effect; this is the same fix in
    // one line, and it belongs here because the arrangement it completes — a readable cookie the
    // page echoes back in a header — is configured here too.
    csrfHandler.setCsrfRequestAttributeName(null);

    return http.csrf(
            it ->
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler))
        .formLogin(it -> it.disable())
        .httpBasic(it -> it.disable())
        .authorizeHttpRequests(
            it ->
                it
                    // The login page itself, and what it needs to draw: somebody who is not logged
                    // in yet still has to be able to read the page that offers to log them in.
                    .requestMatchers(
                        "/",
                        "/index.html",
                        // Prefixes rather than a file each: the page is a set of ES modules and a
                        // stylesheet that imports its parts, and a list of names here is a list
                        // that goes stale — silently, and only for somebody who is not logged in
                        // yet, which is the one state nobody develops in.
                        "/js/**",
                        "/css/**",
                        "/styles.css",
                        "/vendor/**",
                        "/fonts/**",
                        "/favicon.ico")
                    .permitAll()
                    // The public half of what the agent published. Its own token is the
                    // authorisation; see core's ShareController.
                    .requestMatchers("/share/public/**")
                    .permitAll()
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    // Signed in is enough for this one, and only this one. It answers "who am I and
                    // may I use this", so it has to be reachable by somebody the answer is no for —
                    // otherwise a refused login is a page of failed requests with nothing to say
                    // why, which is indistinguishable from the server being broken. It returns
                    // identity and a verdict, never anything the verdict is protecting.
                    .requestMatchers("/api/me")
                    .authenticated()
                    // Everything else, the run stream's websocket handshake included. It needs no
                    // rule of its own: the upgrade is an ordinary GET through this chain, so the
                    // role is required to open the connection, and CSRF does not apply to it
                    // because CSRF does not apply to a safe method. The subscription that follows
                    // is checked again against the journal's owner — see RunStreamSubscriptions,
                    // which cannot rely on this rule alone, since holding the role says a person
                    // may watch their own runs and not anybody else's.
                    .anyRequest()
                    .hasRole("SEAMAN"))
        .exceptionHandling(
            it ->
                // A single-page app asking /api/me must be told "not logged in" rather than handed
                // the HTML of Feishu's login page, which is what the default entry point would do —
                // fetch() follows the redirect and the page sees a 200 full of markup.
                it.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
        .logout(it -> it.logoutSuccessUrl("/").permitAll())
        .oauth2Login(
            it ->
                // Which provider the sign-in button goes to. Configurable rather than fixed
                // because this application can be pointed at either identity provider the
                // repository ships a surface for, and a deployment that installed the Slack app
                // rather than the Feishu one has no Feishu registration to redirect to. Left
                // unset it stays feishu, which is what it has always been.
                it.loginPage("/oauth2/authorization/" + loginProvider)
                    .defaultSuccessUrl("/", true)
                    .userInfoEndpoint(config -> config.userAuthoritiesMapper(authoritiesMapper)))
        .build();
  }
}
