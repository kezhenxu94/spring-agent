package me.kezhenxu94.springagent.integration.websocket.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.config.NativeAuthProperties;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.security.AppleIdentityTokens;
import me.kezhenxu94.springagent.integration.websocket.security.NativeAuthSecurity;
import me.kezhenxu94.springagent.integration.websocket.security.NativeHandoffCodes;
import me.kezhenxu94.springagent.integration.websocket.security.NativeTokens;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * How a native app gets a token: from a browser sign-in handed over by PKCE, or from Sign in with
 * Apple.
 *
 * <p>Outside {@code /api/**} on purpose. That prefix answers an anonymous caller with a bare 401,
 * which is right for the page's fetches and wrong for {@code authorize}: that one is opened in a
 * browser sheet by the app, and an anonymous browser there has to be sent through the identity
 * provider's login and brought back, which is what the ordinary entry point does everywhere else.
 *
 * <p>Every endpoint answers 404 where {@code app.web.auth.native.token-secret} is unset, so an app
 * pointed at a deployment that has not opted in learns that from the first request it makes.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class NativeAuthController {

  private final NativeAuthProperties properties;
  private final WebProperties web;
  private final WebMessages messages;
  private final NativeTokens tokens;
  private final NativeHandoffCodes codes;
  private final AppleIdentityTokens apple;

  /**
   * What the app's sign-in screen should offer, before anybody has signed in: which provider the
   * browser path goes to, whether Apple is a choice, and what the deployment calls itself.
   */
  @GetMapping(NativeAuthSecurity.CONFIG_PATH)
  public Map<String, Object> config() {
    requireEnabled();
    final var out = new LinkedHashMap<String, Object>();
    out.put("enabled", true);
    out.put("browser", true);
    out.put("provider", web.auth().provider());
    out.put("apple", properties.appleEnabled());
    out.put("title", messages.titles());
    return out;
  }

  /**
   * Turns the browser session this request carries into a one-time code and hands it back to the
   * app through its own URL scheme.
   *
   * <p>A refusal is a 400 here and never a redirect: until the scheme has been checked, the
   * redirect target is whatever the caller wrote, and redirecting an error to it would be an open
   * redirect.
   */
  @GetMapping(NativeAuthSecurity.AUTHORIZE_PATH)
  public ResponseEntity<Void> authorize(
      @AuthenticationPrincipal final OAuth2User principal,
      @RequestParam("redirect_uri") final String redirectUri,
      @RequestParam(value = "state", required = false) final String state,
      @RequestParam("code_challenge") final String challenge,
      @RequestParam(value = "code_challenge_method", required = false) final String method) {
    requireEnabled();
    final var user = ChatController.user(principal);
    final var target = allowedRedirect(redirectUri);
    // S256 alone, and stated rather than defaulted: "plain" is the challenge being the verifier,
    // which protects nothing against whoever reads the redirect, and RFC 7636 makes plain the
    // default when the parameter is absent.
    if (!"S256".equals(method)) {
      throw refused("native-auth-bad-challenge");
    }
    if (!NativeHandoffCodes.validChallenge(challenge)) {
      throw refused("native-auth-bad-challenge");
    }
    final var code = codes.issue(user, challenge);
    final var location = UriComponentsBuilder.fromUri(target).queryParam("code", code);
    if (state != null) {
      location.queryParam("state", state);
    }
    log.info("Handing a native sign-in over to {} for {}", target.getScheme(), user.id());
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(location.encode().build().toUri())
        .build();
  }

  /**
   * @param code what the redirect carried
   * @param verifier the secret whose S256 hash was the challenge
   */
  public record TokenRequest(String code, @JsonProperty("code_verifier") String verifier) {}

  @PostMapping(NativeAuthSecurity.TOKEN_PATH)
  public ResponseEntity<Map<String, Object>> token(@RequestBody final TokenRequest body) {
    requireEnabled();
    final var user =
        codes.redeem(body == null ? null : body.code(), body == null ? null : body.verifier());
    if (user.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "invalid_grant", "native-auth-invalid-grant");
    }
    return ResponseEntity.ok(issued(user.get()));
  }

  /**
   * @param identityToken what {@code ASAuthorizationAppleIDCredential.identityToken} held
   * @param name the person's name, which Apple tells the app on the first sign-in only and never
   *     puts in the token; blank on every later one
   */
  public record AppleRequest(@JsonProperty("identity_token") String identityToken, String name) {}

  @PostMapping(NativeAuthSecurity.APPLE_PATH)
  public ResponseEntity<Map<String, Object>> apple(@RequestBody final AppleRequest body) {
    requireEnabled();
    if (!properties.appleEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    final String subject;
    try {
      subject = apple.subject(body == null ? null : body.identityToken());
    } catch (final JwtException | IllegalArgumentException e) {
      log.info("Refusing an Apple identity token: {}", e.getMessage());
      return error(HttpStatus.UNAUTHORIZED, "invalid_token", "native-auth-apple-invalid");
    }
    // After verifying rather than before, so that the refusal is about who they are and not about
    // whether they could produce a token at all — and so the log can say who was turned away.
    if (!properties.openRegistration()) {
      log.info("Refusing Apple sign-in for {}: open registration is off", subject);
      return error(HttpStatus.FORBIDDEN, "registration_closed", "native-auth-apple-closed");
    }
    final var name = body.name() == null ? "" : body.name().trim();
    // No tenant, deliberately: Apple vouches for a person and not for an organisation, and an empty
    // tenant is what keeps an Apple account out of every company-scoped skill, memory and
    // knowledge base this deployment holds.
    return ResponseEntity.ok(
        issued(new WebUser(NativeTokens.APPLE_PREFIX + subject, name, "", "")));
  }

  private Map<String, Object> issued(final WebUser user) {
    final var issued = tokens.issue(user);
    final var out = new LinkedHashMap<String, Object>();
    out.put("access_token", issued.token());
    out.put("token_type", "Bearer");
    out.put("expires_in", issued.expiresIn());
    out.put(
        "user",
        Map.of(
            "userId", user.id(),
            "name", user.name(),
            "avatar", user.avatar(),
            "tenantId", user.tenantId()));
    return out;
  }

  private URI allowedRedirect(final String redirectUri) {
    final URI uri;
    try {
      uri = URI.create(redirectUri);
    } catch (final IllegalArgumentException e) {
      throw refused("native-auth-bad-redirect");
    }
    final var scheme = uri.getScheme();
    if (scheme == null
        || properties.redirectSchemes().stream()
            .noneMatch(it -> it.equalsIgnoreCase(scheme.toLowerCase(Locale.ROOT)))) {
      throw refused("native-auth-bad-redirect");
    }
    return uri;
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private ResponseStatusException refused(final String key) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get(key));
  }

  private ResponseEntity<Map<String, Object>> error(
      final HttpStatus status, final String error, final String key) {
    return ResponseEntity.status(status).body(Map.of("error", error, "message", messages.get(key)));
  }
}
