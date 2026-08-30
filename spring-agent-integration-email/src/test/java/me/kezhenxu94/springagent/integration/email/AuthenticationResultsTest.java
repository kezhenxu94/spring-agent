package me.kezhenxu94.springagent.integration.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The header this module's whole notion of identity rests on.
 *
 * <p>Worth testing hard, because everything it protects is downstream of it and because the
 * interesting inputs are the ones an attacker writes rather than the ones a mail server does. The
 * tests below fall into two halves: that a genuine verdict is read correctly, and that a forged one
 * placed anywhere on the message is not read at all.
 */
class AuthenticationResultsTest {

  private static final String OURS = "mx.example.com";

  @Test
  @DisplayName("a verdict from our own server is read, with the domain it verified")
  void shouldReadOurOwnVerdict() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com; dkim=pass header.d=apache.org; spf=pass"}, OURS);

    assertThat(results).isPresent();
    assertThat(results.orElseThrow().authservId()).isEqualTo("mx.example.com");
    assertThat(results.orElseThrow().dkimPasses())
        .singleElement()
        .satisfies(pass -> assertThat(pass.signingDomain()).isEqualTo("apache.org"));
  }

  @Test
  @DisplayName("a verdict from somebody else's server is not ours to read")
  void shouldIgnoreAnotherServersVerdict() {
    // An intermediate hop's own results are legitimate and say nothing about what we verified.
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"relay.elsewhere.net; dkim=pass header.d=apache.org"}, OURS);

    assertThat(results).isEmpty();
  }

  @Test
  @DisplayName("only the topmost of our own is read, so a forgery below it is never reached")
  void shouldReadOnlyTheTopmost() {
    // The case this class exists for. Headers accumulate downward, so ours is first and anything
    // claiming our name below it was written by the sender. A search for the first header that
    // says "pass" would find the forgery; taking the first that is ours finds the truth.
    final var results =
        AuthenticationResults.firstIn(
            new String[] {
              "mx.example.com; dkim=fail header.d=attacker.example",
              "mx.example.com; dkim=pass header.d=apache.org"
            },
            OURS);

    assertThat(results).isPresent();
    assertThat(results.orElseThrow().dkimPasses()).isEmpty();
  }

  @Test
  @DisplayName("a pass hidden inside a comment is not a pass")
  void shouldNotReadAVerdictOutOfAComment() {
    // Comments are legal anywhere between tokens, so anything scanning the raw text for "dkim=pass"
    // can be handed one that is not a verdict at all.
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com; dkim=fail (tried dkim=pass header.d=apache.org)"}, OURS);

    assertThat(results.orElseThrow().dkimPasses()).isEmpty();
  }

  @Test
  @DisplayName("a comment beside a genuine verdict does not hide it")
  void shouldReadAVerdictBesideAComment() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {
              "mx.example.com; dkim=pass (2048-bit key; unprotected) header.d=apache.org"
            },
            OURS);

    assertThat(results.orElseThrow().dkimPasses())
        .singleElement()
        .satisfies(pass -> assertThat(pass.signingDomain()).isEqualTo("apache.org"));
  }

  @Test
  @DisplayName("nested comments are counted rather than matched")
  void shouldHandleNestedComments() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com; dkim=pass (a (b) c) header.d=apache.org"}, OURS);

    assertThat(results.orElseThrow().dkimPasses())
        .singleElement()
        .satisfies(pass -> assertThat(pass.signingDomain()).isEqualTo("apache.org"));
  }

  @Test
  @DisplayName("a quoted value survives, parentheses in it and all")
  void shouldNotTreatAQuotedParenthesisAsAComment() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com; dkim=pass header.d=\"apache.org\""}, OURS);

    assertThat(results.orElseThrow().dkimPasses())
        .singleElement()
        .satisfies(pass -> assertThat(pass.signingDomain()).isEqualTo("apache.org"));
  }

  @Test
  @DisplayName("several signatures are all reported, since any of them may be the aligned one")
  void shouldReadEverySignature() {
    // A message relayed through a list carries the list's signature and the author's.
    final var results =
        AuthenticationResults.firstIn(
            new String[] {
              "mx.example.com; dkim=pass header.d=lists.apache.org; dkim=pass header.d=apache.org"
            },
            OURS);

    assertThat(results.orElseThrow().dkimPasses())
        .extracting(AuthenticationResults.Result::signingDomain)
        .containsExactly("lists.apache.org", "apache.org");
  }

  @Test
  @DisplayName("a failure, a temporary error and none are all simply not a pass")
  void shouldNotReadAnythingButPassAsPass() {
    for (final var verdict : new String[] {"fail", "none", "temperror", "permerror", "neutral"}) {
      final var results =
          AuthenticationResults.firstIn(
              new String[] {"mx.example.com; dkim=" + verdict + " header.d=apache.org"}, OURS);

      assertThat(results.orElseThrow().dkimPasses()).as(verdict).isEmpty();
    }
  }

  @Test
  @DisplayName("a verdict qualified with a policy is still that verdict")
  void shouldReadAQualifiedVerdict() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com; dkim=pass/policy.something header.d=apache.org"}, OURS);

    assertThat(results.orElseThrow().dkimPasses()).hasSize(1);
  }

  @Test
  @DisplayName("the case of a name is not part of it")
  void shouldNotCareAboutCase() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"MX.Example.COM; DKIM=PASS header.d=Apache.ORG"}, OURS);

    assertThat(results.orElseThrow().dkimPasses())
        .singleElement()
        .satisfies(pass -> assertThat(pass.signingDomain()).isEqualTo("apache.org"));
  }

  @Test
  @DisplayName("no header, no headers at all, and no configured identity are each simply nothing")
  void shouldBeEmptyWithoutSomethingToRead() {
    assertThat(AuthenticationResults.firstIn(null, OURS)).isEmpty();
    assertThat(AuthenticationResults.firstIn(new String[0], OURS)).isEmpty();
    assertThat(AuthenticationResults.firstIn(new String[] {"mx.example.com; dkim=pass"}, null))
        .isEmpty();
    assertThat(AuthenticationResults.firstIn(new String[] {"mx.example.com; dkim=pass"}, "  "))
        .isEmpty();
  }

  @Test
  @DisplayName("a malformed header is not a pass, and does not throw")
  void shouldSurviveNonsense() {
    for (final var header :
        new String[] {"", "   ", ";;;", "mx.example.com", "mx.example.com;", "=;=;=", "((("}) {
      assertThat(AuthenticationResults.firstIn(new String[] {header}, OURS))
          .as(header)
          .satisfiesAnyOf(
              parsed -> assertThat(parsed).isEmpty(),
              parsed -> assertThat(parsed.orElseThrow().dkimPasses()).isEmpty());
    }
  }

  @Test
  @DisplayName("a version number after the identity is not part of the identity")
  void shouldIgnoreAVersionNumber() {
    final var results =
        AuthenticationResults.firstIn(
            new String[] {"mx.example.com 1; dkim=pass header.d=apache.org"}, OURS);

    assertThat(results).isPresent();
    assertThat(results.orElseThrow().dkimPasses()).hasSize(1);
  }
}
