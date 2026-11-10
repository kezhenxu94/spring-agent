package me.kezhenxu94.springagent.integration.websocket.security;

import java.util.List;
import me.kezhenxu94.springagent.integration.websocket.config.NativeAuthProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Reads the identity token Sign in with Apple hands an app, and says whose it is.
 *
 * <p>Verified in full here rather than trusted because the app sent it: the signature against
 * Apple's published keys, the issuer, that it was issued to one of this deployment's own bundle ids
 * — without which a token any other app obtained for the same person would sign them in here — and
 * that it has not expired. Apple's keys are fetched when first needed and cached by the decoder,
 * not at startup, so an unreachable Apple costs an Apple sign-in and never the application.
 */
@Component
public class AppleIdentityTokens {

  private final NativeAuthProperties properties;
  private volatile JwtDecoder decoder;

  public AppleIdentityTokens(final NativeAuthProperties properties) {
    this.properties = properties;
  }

  /**
   * Apple's subject for this person, stable for this team's apps.
   *
   * @throws JwtException for a token that is not Apple's, not for one of our bundle ids, or expired
   */
  public String subject(final String identityToken) {
    final var jwt = decoder().decode(identityToken);
    final var subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new JwtException("The identity token names nobody");
    }
    return subject;
  }

  private JwtDecoder decoder() {
    var current = decoder;
    if (current == null) {
      synchronized (this) {
        current = decoder;
        if (current == null) {
          current = build();
          decoder = current;
        }
      }
    }
    return current;
  }

  private JwtDecoder build() {
    final var built =
        NimbusJwtDecoder.withJwkSetUri(properties.appleJwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    final List<String> audiences = properties.appleClientIds();
    built.setJwtValidator(
        new DelegatingOAuth2TokenValidator<Jwt>(
            JwtValidators.createDefaultWithIssuer(NativeAuthProperties.APPLE_ISSUER),
            jwt ->
                jwt.getAudience() != null
                        && jwt.getAudience().stream().anyMatch(audiences::contains)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error(
                            "invalid_token",
                            "The identity token was issued to an app this deployment does not"
                                + " accept",
                            null))));
    return built;
  }
}
