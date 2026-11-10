package me.kezhenxu94.springagent.integration.websocket.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How a native client — a desktop or mobile app — signs in, under {@code app.web.auth.native}.
 *
 * <p>A record of its own rather than a component of {@link WebProperties.Auth}: {@code native} is a
 * Java keyword, so it cannot be a record component's name, and a component under any other name
 * would be a property spelled differently from the one documented.
 *
 * <p><b>Off unless {@code token-secret} is set, and decided at runtime rather than by a
 * condition.</b> Every bean here exists either way and asks {@link #enabled()} on each request. A
 * {@code @Conditional} would be evaluated during AOT, so a native image built without the variable
 * would carry no endpoint at all whatever the deployment later set — and one built with it would
 * answer with endpoints whose key is whatever the build machine had.
 *
 * @param tokenSecret the HS256 key access tokens are signed with. Base64 if it decodes to at least
 *     32 bytes, and otherwise its own UTF-8 bytes, which then have to be at least 32 — see {@link
 *     #key()}. Blank turns the whole feature off. Changing it signs every phone out at once, which
 *     is also the only way to: a token is not stored anywhere it could be revoked from
 * @param redirectSchemes the URL schemes a sign-in may be handed back to ({@code springagent}).
 *     Checked because the code in that redirect is a credential for anybody who can exchange it,
 *     and an unchecked scheme would let any page bounce a signed-in person's code to wherever it
 *     liked. PKCE already makes a stolen code useless without its verifier; this is the second lock
 * @param appleClientIds the bundle ids Sign in with Apple may have issued an identity token for —
 *     its {@code aud}. Empty turns Apple sign-in off
 * @param openRegistration whether somebody signing in with Apple, who arrives with no tenant and no
 *     identity this deployment has vouched for, is let in at all. Off by default for the reason
 *     {@code app.web.auth.tenant-id} has none: anybody can make an Apple Account, and the agent
 *     acts with the credentials, files and MCP servers of whoever it is running for. Checked again
 *     on every request, so turning it off locks out tokens already issued
 * @param tokenTtl how long an access token lasts. There is no refresh token: a phone whose token
 *     has expired signs in again, which on the browser path is usually one tap, since the identity
 *     provider still has a session
 * @param appleJwksUri where Apple's signing keys are read from. Configurable for a test to point at
 *     keys of its own, not because Apple has more than one
 */
@ConfigurationProperties(prefix = "app.web.auth.native")
public record NativeAuthProperties(
    String tokenSecret,
    List<String> redirectSchemes,
    List<String> appleClientIds,
    boolean openRegistration,
    Duration tokenTtl,
    String appleJwksUri) {

  public static final String APPLE_ISSUER = "https://appleid.apple.com";

  public NativeAuthProperties {
    tokenSecret = tokenSecret == null ? "" : tokenSecret.trim();
    redirectSchemes = clean(redirectSchemes);
    appleClientIds = clean(appleClientIds);
    tokenTtl = tokenTtl == null ? Duration.ofDays(30) : tokenTtl;
    appleJwksUri =
        appleJwksUri == null || appleJwksUri.isBlank()
            ? APPLE_ISSUER + "/auth/keys"
            : appleJwksUri.trim();
  }

  public boolean enabled() {
    return !tokenSecret.isEmpty();
  }

  public boolean appleEnabled() {
    return enabled() && !appleClientIds.isEmpty();
  }

  /**
   * The signing key's bytes.
   *
   * <p>Base64 first, because that is what {@code openssl rand -base64 32} prints and what anybody
   * generating a secret is told to run. A secret that is not base64, or decodes to too few bytes to
   * be a key, is taken as text — so a long passphrase works too, rather than being rejected for not
   * being something nobody said it had to be. Short either way is refused: HS256 with fewer than 32
   * bytes of key is a key somebody can search.
   */
  public byte[] key() {
    try {
      final var decoded = java.util.Base64.getDecoder().decode(tokenSecret);
      if (decoded.length >= 32) {
        return decoded;
      }
    } catch (final IllegalArgumentException notBase64) {
      // Taken as text below.
    }
    final var raw = tokenSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (raw.length < 32) {
      throw new IllegalStateException(
          "app.web.auth.native.token-secret (WEB_AUTH_TOKEN_SECRET) is too short to sign tokens"
              + " with: it needs at least 32 bytes, as base64 or as text. Generate one with"
              + " `openssl rand -base64 32`.");
    }
    return raw;
  }

  private static List<String> clean(final List<String> values) {
    return values == null
        ? List.of()
        : values.stream().filter(it -> it != null && !it.isBlank()).map(String::trim).toList();
  }
}
