package me.kezhenxu94.springagent.integration.websocket.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.config.NativeAuthProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * What an application's {@code SecurityFilterChain} needs so that a native app can use this
 * surface: one call, {@link #configure}, before the application's own rules, and {@link
 * #loginSuccessHandler} where it used to say {@code defaultSuccessUrl("/", true)}.
 *
 * <p>Here rather than in each application because it is the same in each, and two copies of an
 * authentication rule are two places for one of them to be wrong.
 *
 * <p><b>A bearer token is the request's whole identity.</b> Where one is presented it replaces
 * whatever the session held, and a bad one is a 401 there and then — never a fall back to the
 * session, which would let a phone holding an expired token act as whoever last signed in to the
 * same cookie jar, and would make a token that does not work indistinguishable from one that does.
 *
 * <p><b>CSRF is skipped for a request a bearer token authenticated, and only for that.</b> The
 * protection exists because a cookie is sent by the browser on its own, so a page on another origin
 * can make a request carry it. An {@code Authorization} header is never sent on its own: whoever
 * made the request wrote it, so there is nothing ambient to forge with. Exempting on the header's
 * mere presence would be wrong, though — a request carrying a cookie session and some header that
 * did not authenticate would then skip the check while acting as the cookie's owner — so the
 * exemption is keyed on an attribute this filter sets only once the token has been verified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeAuthSecurity {

  /** The paths a native app reaches before it has a token: what to show, and how to get one. */
  public static final String CONFIG_PATH = "/auth/native/config";

  public static final String AUTHORIZE_PATH = "/auth/native/authorize";
  public static final String TOKEN_PATH = "/auth/native/token";
  public static final String APPLE_PATH = "/auth/native/apple";

  /** Set on a request a valid bearer token authenticated. */
  static final String BEARER_AUTHENTICATED = NativeAuthSecurity.class.getName() + ".BEARER";

  private final NativeAuthProperties properties;
  private final NativeTokens tokens;

  /**
   * Adds bearer authentication, the CSRF exemptions it implies, and the public endpoints a native
   * sign-in starts from. Call it before the application's own {@code authorizeHttpRequests}: the
   * rules here are specific paths, and Spring Security refuses a path rule added after {@code
   * anyRequest()}.
   *
   * <p>Inert where no token secret is configured — the filter passes everything through and the
   * endpoints answer 404 — but applied either way, so whether it is on is decided by configuration
   * at runtime and never by which build of the application was deployed.
   */
  public HttpSecurity configure(final HttpSecurity http) throws Exception {
    final var paths = PathPatternRequestMatcher.withDefaults();
    // Before the CSRF filter, which is what makes the exemption below possible to decide: by the
    // time CsrfFilter asks, the token has been verified or the request has been refused.
    http.addFilterBefore(new BearerFilter(), CsrfFilter.class)
        .csrf(
            it ->
                it.ignoringRequestMatchers(
                    request -> request.getAttribute(BEARER_AUTHENTICATED) != null,
                    // Neither of these has a session to forge with: whoever calls them is not
                    // signed in yet, and what they present — a code with its verifier, an Apple
                    // identity token — is the whole of their credential.
                    paths.matcher(HttpMethod.POST, TOKEN_PATH),
                    paths.matcher(HttpMethod.POST, APPLE_PATH)))
        .authorizeHttpRequests(
            it ->
                // The authorize step is deliberately not among them: it is where a browser session
                // is turned into a code, so it needs the session — anyRequest's role rule covers
                // it, and an anonymous caller is sent through the ordinary login and back.
                it.requestMatchers(
                        paths.matcher(HttpMethod.GET, CONFIG_PATH),
                        paths.matcher(HttpMethod.POST, TOKEN_PATH),
                        paths.matcher(HttpMethod.POST, APPLE_PATH))
                    .permitAll());
    return http;
  }

  /**
   * Where a browser goes once it has signed in: back to the native sign-in that sent it to log in,
   * and to the page otherwise.
   *
   * <p>Not {@code defaultSuccessUrl("/", true)}, which ignores where the login came from, so a
   * phone's sign-in sheet would land on the web page and never reach the redirect that hands the
   * code back. Not that with {@code false} either: every other request that could be saved — the
   * run stream's websocket upgrade, a stray asset — is one nobody wants to land on, which is why
   * the page always went to {@code /}. So only the one path is honoured.
   */
  public AuthenticationSuccessHandler loginSuccessHandler() {
    final var toPage = new SimpleUrlAuthenticationSuccessHandler("/");
    toPage.setAlwaysUseDefaultTargetUrl(true);
    final var toSaved = new SavedRequestAwareAuthenticationSuccessHandler();
    toSaved.setDefaultTargetUrl("/");
    final var cache = new HttpSessionRequestCache();
    return (request, response, authentication) -> {
      final var saved = cache.getRequest(request, response);
      if (saved != null && isAuthorize(saved.getRedirectUrl())) {
        toSaved.onAuthenticationSuccess(request, response, authentication);
      } else {
        toPage.onAuthenticationSuccess(request, response, authentication);
      }
    };
  }

  private static boolean isAuthorize(final String url) {
    try {
      final var path = java.net.URI.create(url).getPath();
      return path != null && path.endsWith(AUTHORIZE_PATH);
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private final class BearerFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final FilterChain chain)
        throws ServletException, IOException {
      final var header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (!properties.enabled()
          || header == null
          || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
        chain.doFilter(request, response);
        return;
      }
      final var strategy = SecurityContextHolder.getContextHolderStrategy();
      try {
        final var authentication = tokens.authenticate(header.substring(PREFIX.length()).trim());
        final var context = strategy.createEmptyContext();
        context.setAuthentication(authentication);
        // Into the holder for this request alone and never into the session: nothing saves it, so
        // a bearer request leaves no trace a later cookie request could inherit.
        strategy.setContext(context);
      } catch (final JwtException | IllegalArgumentException e) {
        log.debug("Refusing a bearer token on {}: {}", request.getRequestURI(), e.getMessage());
        strategy.clearContext();
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        // setStatus rather than sendError: an error dispatch goes through this chain again with
        // no token-derived identity, and its entry point would answer a phone with a login
        // redirect.
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return;
      }
      request.setAttribute(BEARER_AUTHENTICATED, Boolean.TRUE);
      chain.doFilter(request, response);
    }
  }
}
