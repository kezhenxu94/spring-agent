package me.kezhenxu94.springagent.integration.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import me.kezhenxu94.springagent.integration.email.config.EmailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One mail as one observation, and the two identifiers that decide what becomes of it. */
class MailObservationsTest {

  private static final String VERDICT =
      "Authentication-Results: mx.example.com; dkim=pass header.d=apache.org";

  private final MailObservations observations =
      new MailObservations(
          EmailProperties.builder().host("imap.example.com").authservId("mx.example.com").build());

  private static MimeMessage message(final String raw) throws Exception {
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
  }

  private static MimeMessage mail(final String headers, final String body) throws Exception {
    return message(VERDICT + "\nFrom: someone@apache.org\n" + headers + "\n\n" + body + "\n");
  }

  @Test
  @DisplayName("a message from an authenticated sender becomes one observation")
  void shouldReadAMessage() throws Exception {
    final var observation = observations.of(mail("Subject: the build is red", ""), 42L, 7L);

    assertThat(observation.source()).isEqualTo("email");
    assertThat(observation.kind()).isEqualTo("mail.received");
    assertThat(observation.actor()).isEqualTo("someone@apache.org");
    assertThat(observation.title()).isEqualTo("the build is red");
    assertThat(observation.summary()).contains("someone@apache.org").contains("the build is red");
    // A webhook knows nowhere to talk, and neither does a mailbox. Nothing replies to a sender.
    assertThat(observation.route().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("a message nobody vouched for is read, and has no actor")
  void shouldReadAnUnvouchedMessageWithoutAnActor() throws Exception {
    // Mapping is not policy: the observation is made so that somebody embedding this module can
    // log or count what arrives, and the actor is null so that MailObservationHandler — and
    // TrustedActors after it — can tell "nobody vouched for this" from "somebody we don't want".
    final var unsigned = message("From: mallory@attacker.example\nSubject: hello\n\nbody\n");

    final var observation = observations.of(unsigned, 42L, 7L);

    assertThat(observation.actor()).isNull();
    // Keyed on the uid alone: the address it claims is nobody's fact but its author's.
    assertThat(observation.correlationKey()).isEqualTo("email:42/7");
    // And still carried as evidence, which is all an unauthenticated From has ever been.
    assertThat(observation.summary()).contains("mallory@attacker.example");
    assertThat(observation.payloadJson()).contains("mallory@attacker.example");
  }

  @Test
  @DisplayName("unvouched mail cannot correlate itself into a thread")
  void shouldNotCorrelateUnvouchedMail() throws Exception {
    // The headers that make a thread are the sender's to write, and nothing has established who
    // the sender is. Honouring them would let anybody who learned the address gather their own
    // messages — or somebody else's — into one situation, for a consumer that reported them.
    final var first =
        message(
            "From: mallory@attacker.example\nReferences: <root@apache.org>\nSubject: x\n\nbody\n");
    final var second =
        message(
            "From: mallory@attacker.example\nReferences: <root@apache.org>\nSubject: x\n\nbody\n");

    assertThat(observations.of(first, 42L, 7L).correlationKey())
        .isNotEqualTo(observations.of(second, 42L, 8L).correlationKey());
  }

  @Test
  @DisplayName("the delivery id is the server's numbering, never the sender's Message-ID")
  void shouldMintTheDeliveryIdFromTheUid() throws Exception {
    // The whole point. Message-ID is written by the sender, so two different messages can claim the
    // same one — and the second would then be swallowed as a redelivery by a claim that never
    // expires. UIDVALIDITY is in the pair because a uid is only unique within a generation, and a
    // mailbox re-created on the server renumbers from the start.
    final var observation =
        observations.of(mail("Message-ID: <forged@attacker.example>\nSubject: x", ""), 42L, 7L);

    assertThat(observation.deliveryId()).isEqualTo("42:7");
  }

  @Test
  @DisplayName("a reply joins the thread it answers, within its sender")
  void shouldCorrelateByThread() throws Exception {
    final var first =
        observations.of(mail("Message-ID: <root@apache.org>\nSubject: x", ""), 42L, 7L);
    final var reply =
        observations.of(mail("References: <root@apache.org>\nSubject: Re: x", ""), 42L, 8L);
    final var later =
        observations.of(
            mail("References: <root@apache.org> <second@apache.org>\nSubject: Re: x", ""), 42L, 9L);

    // The root of the thread, not the message replied to, so a long thread stays one situation.
    assertThat(reply.correlationKey()).isEqualTo(later.correlationKey());
    // And a thread's first message correlates on its own uid rather than on an id it chose, so two
    // unrelated messages cannot claim to be one conversation.
    assertThat(first.correlationKey()).isNotEqualTo(reply.correlationKey());
  }

  @Test
  @DisplayName("a sender cannot put their mail into somebody else's thread")
  void shouldScopeTheThreadToItsSender() throws Exception {
    // References and In-Reply-To are written by the sender and checked by nobody. Without the
    // sender in the key, guessing another's message id would put mail into their situation — and
    // then be shown that situation's evidence in the brief.
    final var theirs =
        observations.of(mail("References: <root@apache.org>\nSubject: x", ""), 42L, 7L);
    final var impostor =
        message(
            "Authentication-Results: mx.example.com; dkim=pass header.d=other.example\n"
                + "From: mallory@other.example\n"
                + "References: <root@apache.org>\nSubject: x\n\nbody\n");

    assertThat(observations.of(impostor, 42L, 8L).correlationKey())
        .isNotEqualTo(theirs.correlationKey());
  }

  @Test
  @DisplayName("a correlation key too long for the column is hashed rather than cut")
  void shouldHashAnOverlongKey() throws Exception {
    // Both halves are the sender's to make as long as they like. Cutting would merge two threads
    // whose ids share a prefix into one situation.
    final var observation =
        observations.of(
            mail("References: <" + "x".repeat(400) + "@apache.org>\nSubject: x", ""), 42L, 7L);

    assertThat(observation.correlationKey()).startsWith("email:").hasSizeLessThan(64);
  }

  @Test
  @DisplayName("a message with no subject still names its situation")
  void shouldNameASubjectlessMessage() throws Exception {
    final var observation = observations.of(mail("X-Nothing: x", "body"), 42L, 7L);

    assertThat(observation.title()).isEqualTo("Mail from someone@apache.org");
  }

  @Test
  @DisplayName("the body is evidence, and the payload keeps it as data")
  void shouldCarryTheBody() throws Exception {
    final var observation =
        observations.of(mail("Subject: x", "the disk filled up again"), 42L, 7L);

    assertThat(observation.summary()).contains("the disk filled up again");
    assertThat(observation.payloadJson()).contains("the disk filled up again").startsWith("{");
  }
}
