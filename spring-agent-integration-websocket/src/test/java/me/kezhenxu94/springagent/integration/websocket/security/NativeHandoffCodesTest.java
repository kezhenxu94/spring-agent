package me.kezhenxu94.springagent.integration.websocket.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import me.kezhenxu94.springagent.integration.websocket.config.NativeAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NativeHandoffCodesTest {

  private static final WebUser ME = new WebUser("ou_me", "Me", "", "tenant");
  private static final String VERIFIER = "a".repeat(43);

  @Test
  @DisplayName("a code is redeemed once, with the verifier its challenge was made from")
  void redeemOnce() {
    final var codes = new NativeHandoffCodes();
    final var code = codes.issue(ME, NativeHandoffCodes.s256(VERIFIER));
    assertThat(codes.redeem(code, VERIFIER)).contains(ME);
    assertThat(codes.redeem(code, VERIFIER)).isEmpty();
  }

  @Test
  @DisplayName(
      "a wrong verifier spends the code, so whoever has only the code cannot keep guessing")
  void wrongVerifierSpendsTheCode() {
    final var codes = new NativeHandoffCodes();
    final var code = codes.issue(ME, NativeHandoffCodes.s256(VERIFIER));
    assertThat(codes.redeem(code, "b".repeat(43))).isEmpty();
    assertThat(codes.redeem(code, VERIFIER)).isEmpty();
  }

  @Test
  @DisplayName("an expired code is refused")
  void expired() {
    final var now =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    final var clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    final var codes = new NativeHandoffCodes(clock);
    final var code = codes.issue(ME, NativeHandoffCodes.s256(VERIFIER));
    now.set(now.get().plus(NativeHandoffCodes.TTL).plusSeconds(1));
    assertThat(codes.redeem(code, VERIFIER)).isEmpty();
  }

  @Test
  @DisplayName("S256 is the RFC 7636 appendix B example")
  void s256MatchesTheRfc() {
    assertThat(NativeHandoffCodes.s256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    assertThat(NativeHandoffCodes.validChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        .isTrue();
    assertThat(NativeHandoffCodes.validChallenge("short")).isFalse();
  }

  @Test
  @DisplayName("the secret is base64 where it decodes to a key, text otherwise, and never short")
  void secretHandling() {
    final var base64 = java.util.Base64.getEncoder().encodeToString(new byte[32]);
    assertThat(properties(base64).key()).hasSize(32).containsOnly((byte) 0);
    final var text = "a passphrase that is long enough to be a key!";
    assertThat(properties(text).key()).isEqualTo(text.getBytes());
    assertThatThrownBy(() -> properties("too short").key())
        .isInstanceOf(IllegalStateException.class);
    assertThat(properties("").enabled()).isFalse();
  }

  private static NativeAuthProperties properties(final String secret) {
    return new NativeAuthProperties(secret, List.of(), List.of(), false, null, null);
  }
}
