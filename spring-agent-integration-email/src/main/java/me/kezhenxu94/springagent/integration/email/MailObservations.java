package me.kezhenxu94.springagent.integration.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeUtility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.Actor;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.integration.email.config.EmailProperties;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One message read from the mailbox, as an {@link Observation}.
 *
 * <p>A class of its own rather than a method on the watcher, so that everything with a decision in
 * it can be tested against a {@code MimeMessage} built in memory, and the watcher is left with only
 * the parts that need a server.
 *
 * <p>A mapping and not a policy. Whether a message may wake the agent is decided by {@link
 * MailObservationHandler}, which is what lets somebody embedding this module read the same mailbox
 * for a purpose of their own — counting what arrives, warning about what does not — without taking
 * on the agent's rules about whose mail counts.
 */
@Slf4j
@RequiredArgsConstructor
public class MailObservations {

  private static final JsonMapper MAPPER = new JsonMapper();

  /** Stands in for the {@code From} of a message that names no single sender. */
  private static final String UNKNOWN_SENDER = "(unknown sender)";

  /**
   * How long a correlation key may be before it is hashed instead.
   *
   * <p>The parts of it are a sender's address and a message id, both of which the sender chooses
   * the length of, and the column it lands in has a bound. Hashed rather than truncated: two
   * threads whose ids share a long prefix would otherwise become one situation.
   */
  private static final int MAX_KEY_LENGTH = 180;

  private final EmailProperties properties;

  /**
   * The observation for {@code message} — every message, whether anybody vouched for it or not.
   *
   * <p>Authentication is reported here rather than acted on: {@link Observation#actor()} is an
   * {@link Actor#authenticated} sender where DKIM vouched for one and an {@link Actor#claimed} one
   * — the address in {@code From}, which anybody may write — where nothing did. Both are reported,
   * because an intake watching this mailbox for reasons of its own usually wants to know who was at
   * the door rather than only that somebody was. What keeps an unvouched message away from the
   * agent is {@code TrustedActors} refusing an actor with no authenticated name, one layer on.
   *
   * <p>What that costs is that mail nobody vouched for is parsed and its body reduced before it is
   * thrown away. Bounded rather than open-ended: {@code app.email.max-body-length} caps the text
   * kept from one message and the receiver's fetch size caps how many are read in a pass.
   *
   * @param uidValidity the folder's generation, from {@code UIDFolder#getUIDValidity}
   * @param uid the message's uid within that generation
   */
  public Observation of(final Message message, final long uidValidity, final long uid) {
    final var sender =
        SenderIdentity.of(message, properties.authservId(), "Authentication-Results").orElse(null);
    // What the message says about itself, for the parts of an observation that are evidence. On the
    // authenticated path it is the same string as the sender above, since a DKIM verdict vouches
    // for the address as written; on any other it is a name nobody checked, and it stays out of the
    // correlation key for exactly that reason.
    final var claimed = SenderIdentity.claimedFrom(message).orElse(null);
    final var from = claimed == null ? UNKNOWN_SENDER : claimed;
    final var subject = subjectOf(message);
    final var body = MessageText.bodyOf(message, properties.maxBodyLength());

    return Observation.builder()
        .source(EmailProperties.SOURCE)
        // The server's own numbering, never the Message-ID header. See deliveryId below.
        .deliveryId(uidValidity + ":" + uid)
        .kind("mail.received")
        .correlationKey(correlationKey(sender, message, uidValidity, uid))
        .title(subject.isBlank() ? "Mail from " + from : subject)
        .summary(summary(from, subject, body))
        // Authenticated where DKIM vouched, claimed where it did not, and absent only for a message
        // naming no single sender at all. The stand-in used for display above is deliberately not
        // reported here: "(unknown sender)" is a phrase of ours, and an actor is the event's.
        .actor(sender == null ? Actor.claimed(claimed) : Actor.authenticated(sender))
        .payloadJson(payload(from, subject, body))
        // Left to default to now, not the Date header. Date is written by the sender, and a
        // backdated one would land an observation already older than the quiet period that
        // closes a situation — which is to say, arrive pre-resolved.
        .build();
  }

  /**
   * What groups a message with the ones before it: the thread, within the sender.
   *
   * <p>The thread alone would not do, and this is the one place where the difference matters. The
   * {@code References} and {@code In-Reply-To} headers are written by whoever sent the message and
   * are checked by nobody, so a sender who guesses another's message id could put their mail into
   * somebody else's situation — and then be shown that situation's evidence in the brief. Keying by
   * the authenticated sender as well means a sender can only ever join their own threads.
   *
   * <p>Falling back to the uid rather than to the message's own {@code Message-ID} where there is
   * no thread: a first message in a thread correlates with nothing yet, and using an id the sender
   * chose would let two unrelated messages claim to be one conversation.
   *
   * <p>Where nobody vouched for the sender the key is the uid and nothing else — not the thread the
   * message names, not the address it claims to come from. Both are its author's to write, and
   * honouring either would leave a consumer that chose to report unvouched mail with a situation
   * anybody who learned the address could join.
   */
  private String correlationKey(
      final String sender, final Message message, final long uidValidity, final long uid) {
    if (sender == null) {
      return EmailProperties.SOURCE + ":" + uidValidity + "/" + uid;
    }
    final var thread = threadRoot(message);
    final var key =
        EmailProperties.SOURCE
            + ":"
            + sender
            + ":"
            + (thread == null ? uidValidity + "/" + uid : thread);
    return key.length() <= MAX_KEY_LENGTH ? key : shortened(key);
  }

  private static String threadRoot(final Message message) {
    final var references = header(message, "References");
    if (references != null && !references.isBlank()) {
      // The first entry is the root of the thread; the rest are the path down to this message.
      final var first = references.trim().split("\\s+")[0];
      if (!first.isBlank()) {
        return first;
      }
    }
    final var inReplyTo = header(message, "In-Reply-To");
    return inReplyTo == null || inReplyTo.isBlank() ? null : inReplyTo.trim();
  }

  private String summary(final String from, final String subject, final String body) {
    final var headline = subject.isBlank() ? "(no subject)" : subject;
    return "From " + from + " — " + headline + (body.isBlank() ? "" : "\n" + body);
  }

  private String payload(final String from, final String subject, final String body) {
    final ObjectNode node = MAPPER.createObjectNode();
    node.put("from", from);
    node.put("subject", subject);
    node.put("body", body);
    return node.toString();
  }

  /** The subject, decoded from whatever encoding it was folded into, and cleaned like the body. */
  private static String subjectOf(final Message message) {
    try {
      final var subject = message.getSubject();
      return subject == null ? "" : MessageText.truncate(MessageText.clean(subject), 512);
    } catch (MessagingException e) {
      log.debug("Could not read a subject", e);
      return "";
    }
  }

  private static String header(final Message message, final String name) {
    try {
      final var values = message.getHeader(name);
      return values == null || values.length == 0 ? null : MimeUtility.unfold(values[0]);
    } catch (MessagingException e) {
      return null;
    }
  }

  private static String shortened(final String key) {
    try {
      final var digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return EmailProperties.SOURCE + ":" + HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
