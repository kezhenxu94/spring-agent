package me.kezhenxu94.springagent.integration.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a From header is an identity, which is the question the whole source turns on.
 *
 * <p>Messages are parsed from their wire form rather than built with setters, so that header order
 * is real: the ordering of {@code Authentication-Results} headers is load-bearing, and a fixture
 * that could not express "the attacker's is below ours" would not test the thing worth testing.
 */
class SenderIdentityTest {

  private static final String OURS = "mx.example.com";

  private static MimeMessage message(final String raw) throws Exception {
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("a signed message from a domain our own server verified is that sender")
  void shouldAuthenticateAVerifiedSender() throws Exception {
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=apache.org
            From: Someone <someone@apache.org>
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results"))
        .contains("someone@apache.org");
  }

  @Test
  @DisplayName("a message with no Authentication-Results at all is from nobody")
  void shouldNotAuthenticateWithoutAVerdict() throws Exception {
    // The ordinary case for a mailbox fed by something that does not verify, and the reason
    // app.email.authserv-id has no default: this is what a wrong one looks like.
    final var mail =
        message(
            """
            From: Someone <someone@apache.org>
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results")).isEmpty();
  }

  @Test
  @DisplayName("a forged verdict below our server's own is never reached")
  void shouldIgnoreAForgeryBelowOurOwn() throws Exception {
    // What an attacker actually sends: their own Authentication-Results, in the message they
    // compose, naming our server. Ours is prepended above it on arrival.
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=none
            Authentication-Results: mx.example.com; dkim=pass header.d=apache.org
            From: Someone <someone@apache.org>
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results")).isEmpty();
  }

  @Test
  @DisplayName("a verdict for a domain other than the sender's does not vouch for the sender")
  void shouldRequireAlignment() throws Exception {
    // A valid signature by somebody else. Without the alignment check, anybody who can get any
    // message signed by any domain could claim any From they liked.
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=attacker.example
            From: Someone <someone@apache.org>
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results")).isEmpty();
  }

  @Test
  @DisplayName("a subdomain is vouched for by its parent, and a parent not by its subdomain")
  void shouldAlignOnlyDownwards() {
    assertThat(SenderIdentity.aligned("apache.org", "apache.org")).isTrue();
    assertThat(SenderIdentity.aligned("lists.apache.org", "apache.org")).isTrue();
    // The direction that matters: anybody who can publish a key under a subdomain must not be able
    // to speak for the parent.
    assertThat(SenderIdentity.aligned("apache.org", "lists.apache.org")).isFalse();
    // And no accidental suffix matching — notapache.org is not a subdomain of apache.org.
    assertThat(SenderIdentity.aligned("notapache.org", "apache.org")).isFalse();
  }

  @Test
  @DisplayName("a From naming two people names nobody")
  void shouldRefuseMoreThanOneSender() throws Exception {
    // Legal, vanishingly rare, and with no single answer to "who sent this". Taking the first would
    // let a message list a trusted sender beside whoever actually wrote it.
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=apache.org
            From: someone@apache.org, mallory@attacker.example
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results")).isEmpty();
  }

  @Test
  @DisplayName("the address is lowercased, since that is how the rules are written")
  void shouldNormaliseCase() throws Exception {
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=Apache.ORG
            From: Someone <SomeOne@Apache.ORG>
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results"))
        .contains("someone@apache.org");
  }

  @Test
  @DisplayName("one of several signatures aligning is enough")
  void shouldAcceptAnyAlignedSignature() throws Exception {
    // A mailing list signs what it relays, alongside the author's own signature.
    final var mail =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=mailer.example; dkim=pass header.d=apache.org
            From: someone@apache.org
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(mail, OURS, "Authentication-Results"))
        .contains("someone@apache.org");
  }

  @Test
  @DisplayName("the identities actually on a message are recoverable, for a mismatched setting")
  void shouldNameTheIdentitiesItSaw() throws Exception {
    // Not about authentication: this is the troubleshooting path. A wrong app.email.authserv-id is
    // the one misconfiguration that produces silence rather than an error, and what somebody needs
    // in order to fix it is the list of names that were actually on the message — usually one of
    // them is the value they should have configured. SenderIdentity logs exactly this list.
    final var headers =
        new String[] {
          "mx.provider.example; dkim=pass header.d=apache.org",
          "relay.elsewhere.net; dkim=pass header.d=apache.org"
        };

    assertThat(AuthenticationResults.identitiesIn(headers))
        .containsExactly("mx.provider.example", "relay.elsewhere.net");
    // And the same message authenticates nobody while we are looking for a name that is not there.
    assertThat(AuthenticationResults.firstIn(headers, "mx.example.com")).isEmpty();
  }

  @Test
  @DisplayName("a message with no From, or an unparseable one, is from nobody")
  void shouldSurviveAMissingFrom() throws Exception {
    final var none =
        message(
            """
            Authentication-Results: mx.example.com; dkim=pass header.d=apache.org
            Subject: hello

            body
            """);

    assertThat(SenderIdentity.of(none, OURS, "Authentication-Results")).isEmpty();
  }
}
