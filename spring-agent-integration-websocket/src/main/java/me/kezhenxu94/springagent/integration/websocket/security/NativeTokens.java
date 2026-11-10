package me.kezhenxu94.springagent.integration.websocket.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import me.kezhenxu94.springagent.integration.websocket.config.NativeAuthProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * The access tokens a native app carries instead of a session cookie: minted here, read back here.
 *
 * <p>A JWT signed with this deployment's own secret rather than a row in a store, because the store
 * would have to be every persistence backend's and the only thing it would buy is revoking one
 * phone — which rotating the secret does for all of them, and which a TTL bounds anyway.
 *
 * <p><b>The token is turned back into exactly what a browser login produces</b>: an {@link
 * OAuth2AuthenticationToken} over a {@link DefaultOAuth2User} carrying {@code open_id}, {@code
 * name}, {@code avatar_url} and {@code tenant_key}. That is the whole reason it works with every
 * endpoint unchanged — each one reads {@code @AuthenticationPrincipal OAuth2User} and {@link
 * WebUser#of}, and the run stream reads the same principal off the websocket upgrade. A token of a
 * different shape would be a second identity model to keep in step with the first.
 */
@Component
public class NativeTokens {

  /** Who minted it, so a token signed with the same secret for some other purpose is not one. */
  static final String ISSUER = "spring-agent";

  public static final String NAME_CLAIM = "name";
  public static final String AVATAR_CLAIM = "avatar_url";
  public static final String TENANT_CLAIM = "tenant_key";

  /** The prefix of an identity Apple vouched for and nobody in this deployment did. */
  public static final String APPLE_PREFIX = "apple:";

  /** What the principal's attributes are keyed by, mirroring the Feishu profile they stand for. */
  private static final String ID_ATTRIBUTE = "open_id";

  /**
   * What the principal is registered as. Never used to log in with — a token is not an OAuth2 login
   * — but {@link OAuth2AuthenticationToken} requires one, and naming it says where it came from in
   * a log line.
   */
  public static final String REGISTRATION = "native";

  private final NativeAuthProperties properties;
  private final NimbusJwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  public NativeTokens(final NativeAuthProperties properties) {
    this.properties = properties;
    if (!properties.enabled()) {
      this.encoder = null;
      this.decoder = null;
      return;
    }
    // At startup, so that a secret too short to be a key stops the application rather than every
    // sign-in after it.
    final var key = new SecretKeySpec(properties.key(), "HmacSHA256");
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    final var decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    final OAuth2TokenValidator<Jwt> validator =
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER));
    decoder.setJwtValidator(validator);
    this.decoder = decoder;
  }

  /** A signed token for {@code user}, and how many seconds it lasts. */
  public Issued issue(final WebUser user) {
    requireEnabled();
    final var now = Instant.now();
    final var ttl = properties.tokenTtl();
    final var claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.id())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim(NAME_CLAIM, user.name())
            .claim(AVATAR_CLAIM, user.avatar())
            .claim(TENANT_CLAIM, user.tenantId())
            .build();
    final var token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new Issued(token, ttl.toSeconds());
  }

  /**
   * The authentication {@code token} stands for.
   *
   * @throws JwtException for a token that is not one of ours, is malformed or has expired, or names
   *     an Apple identity while this deployment no longer lets those in
   */
  public OAuth2AuthenticationToken authenticate(final String token) {
    requireEnabled();
    final var jwt = decoder.decode(token);
    final var id = jwt.getSubject();
    if (id == null || id.isBlank()) {
      throw new JwtException("The token names nobody");
    }
    // Asked again here rather than only when the token was issued: switching open registration off
    // is how a deployment closes the door it opened, and a door that stays open for every token it
    // already handed out for the next thirty days is not closed.
    if (id.startsWith(APPLE_PREFIX) && !properties.openRegistration()) {
      throw new JwtException("Sign in with Apple is no longer open on this deployment");
    }
    final var attributes =
        Map.<String, Object>of(
            ID_ATTRIBUTE,
            id,
            NAME_CLAIM,
            nullToEmpty(jwt.getClaimAsString(NAME_CLAIM)),
            AVATAR_CLAIM,
            nullToEmpty(jwt.getClaimAsString(AVATAR_CLAIM)),
            TENANT_CLAIM,
            nullToEmpty(jwt.getClaimAsString(TENANT_CLAIM)));
    final var authorities = List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE));
    final var principal = new DefaultOAuth2User(authorities, attributes, ID_ATTRIBUTE);
    return new OAuth2AuthenticationToken(principal, authorities, REGISTRATION);
  }

  private void requireEnabled() {
    if (encoder == null) {
      throw new IllegalStateException("Native sign-in is off: app.web.auth.native.token-secret");
    }
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  /**
   * @param token the signed JWT
   * @param expiresIn its lifetime in seconds, as OAuth2's {@code expires_in} reports it
   */
  public record Issued(String token, long expiresIn) {}
}
