package me.kezhenxu94.springagent.integration.websocket.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one-time codes that carry a browser sign-in over to a native app: the authorization code of
 * OAuth2's code flow with PKCE (RFC 7636), and nothing more of OAuth2 than that.
 *
 * <p>In memory, so the exchange has to reach the replica that issued the code. That is a deliberate
 * trade: a code lives five minutes and is used once within seconds of being issued, and a store
 * shared across replicas would have to be every persistence backend's for the sake of a value that
 * is never read twice. A deployment with several replicas and no session affinity sees a phone's
 * sign-in fail with {@code invalid_grant} and the phone try again, which is the case to recognise.
 *
 * <p><b>Bound to the challenge, so the code alone is worth nothing.</b> The redirect that carries
 * it goes through the operating system's URL handling, where another app registered for the same
 * scheme could read it; only the app that started the sign-in holds the verifier whose hash the
 * challenge is.
 */
@Component
public class NativeHandoffCodes {

  static final Duration TTL = Duration.ofMinutes(5);

  /** RFC 7636's {@code code-verifier}: 43 to 128 unreserved characters. */
  private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");

  /** A base64url SHA-256, which is 43 characters with no padding. */
  private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9\\-_]{43}$");

  private final SecureRandom random = new SecureRandom();
  private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final Clock clock;

  @Autowired
  public NativeHandoffCodes() {
    this(Clock.systemUTC());
  }

  NativeHandoffCodes(final Clock clock) {
    this.clock = clock;
  }

  private record Pending(WebUser user, String challenge, Instant expiresAt) {}

  /** Whether {@code challenge} has the shape an S256 challenge has to have. */
  public static boolean validChallenge(final String challenge) {
    return challenge != null && CHALLENGE.matcher(challenge).matches();
  }

  /** A fresh code for {@code user}, redeemable once by whoever holds the challenge's verifier. */
  public String issue(final WebUser user, final String challenge) {
    sweep();
    final var bytes = new byte[32];
    random.nextBytes(bytes);
    final var code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    pending.put(code, new Pending(user, challenge, clock.instant().plus(TTL)));
    return code;
  }

  /**
   * Who signed in, if {@code code} is one of ours, unexpired and unused, and {@code verifier}
   * hashes to its challenge.
   *
   * <p>The code is spent by the attempt, whether or not the verifier matches: a wrong verifier is
   * either a bug or somebody who has the code and not the verifier, and the second must not get to
   * keep guessing.
   */
  public Optional<WebUser> redeem(final String code, final String verifier) {
    if (code == null) {
      return Optional.empty();
    }
    final var found = pending.remove(code);
    if (found == null || found.expiresAt().isBefore(clock.instant())) {
      return Optional.empty();
    }
    if (verifier == null || !VERIFIER.matcher(verifier).matches()) {
      return Optional.empty();
    }
    final var hashed = s256(verifier);
    if (!MessageDigest.isEqual(
        hashed.getBytes(StandardCharsets.US_ASCII),
        found.challenge().getBytes(StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    return Optional.of(found.user());
  }

  /** {@code BASE64URL(SHA256(ASCII(verifier)))}, which is what S256 means. */
  public static String s256(final String verifier) {
    try {
      final var digest =
          MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every JVM has SHA-256", e);
    }
  }

  /**
   * Drops codes nobody redeemed. On issue rather than on a timer, since issuing is the only thing
   * that grows the map — so it cannot outgrow what was issued in the last five minutes.
   */
  private void sweep() {
    final var now = clock.instant();
    pending.values().removeIf(it -> it.expiresAt().isBefore(now));
  }
}
