package me.kezhenxu94.springagent.integration.email;

import jakarta.mail.Folder;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.integration.email.config.EmailProperties;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;

/**
 * One message the adapter fetched, reported as an observation.
 *
 * <p>The whole of this module's contribution to Spring Integration's side of things: the adapter
 * holds the connection and decides when there is mail, and this decides what that mail is. Kept a
 * plain {@link MessageHandler} on a direct channel rather than a flow, so the seam between the two
 * is one method.
 *
 * <p><b>Reports every message, and decides about none of them.</b> {@link MailObservations} says
 * whether anybody vouched for the sender by reporting an authenticated actor or a merely claimed
 * one, and this passes on what it made either way, as every other source in this runtime does.
 * Whose mail may wake the agent is one question answered in one place — {@code TrustedActors},
 * against {@code app.events.sources.email.trusted-actors}, which refuses an actor nothing vouched
 * for and which {@code EmailAutoConfiguration.EmailSourceCheck} refuses to start without. Dropping
 * here as well would put a second silencer on the path, and in the one spot that silences it for
 * every intake at once: an application reading this mailbox for a purpose of its own — counting
 * what arrives, warning about mail from a stranger — would never see the messages it exists to warn
 * about. An intake that wants the deployment's own answer rather than its own asks {@code
 * TrustedActors}, which is a bean.
 *
 * <p>Never throws. An exception here would travel back up the adapter's dispatch and, depending on
 * how the adapter is feeling about it, either be logged as an error event or leave the message
 * unflagged to be read again on the next pass — for ever, if whatever is wrong with the message is
 * a property of the message. A message this cannot make sense of is dropped with a line in the log,
 * which is the outcome that stops it blocking the mail behind it.
 */
@Slf4j
@RequiredArgsConstructor
public class MailObservationHandler implements MessageHandler {

  private final EmailProperties properties;
  private final EventIntakes intakes;
  private final MailObservations observations;

  @Override
  public void handleMessage(final Message<?> message) {
    if (intakes.isEmpty()) {
      return;
    }
    if (!(message.getPayload() instanceof MimeMessage mail)) {
      log.warn("Ignoring a {} where a mail was due", message.getPayload().getClass());
      return;
    }
    try {
      final var folder = mail.getFolder();
      if (!(folder instanceof UIDFolder uids)) {
        // Every IMAP folder is one. Said rather than assumed, because the cast is what the delivery
        // id rests on and a POP3 receiver wired here by mistake would otherwise fail obscurely.
        log.warn("{} is not a UID folder, so a delivery id cannot be minted", describe(folder));
        return;
      }
      final var uid = uids.getUID(mail);
      final var observation = observations.of(mail, uids.getUIDValidity(), uid);
      // At info because the whole point of the source is that this happened, and because it is one
      // line per message rather than per poll: a mailbox busy enough for this to be noise is one
      // busy enough that the situations it opens are the noise instead.
      //
      // The actor prints the address either way and marks it unverified where nothing vouched for
      // it, which is what makes the line useful for the case it is usually read in: mail that
      // arrived and went no further, where the question is who was trying. Why nobody vouched for
      // it is said by SenderIdentity, which has the detail.
      log.info("Reporting message {} in {} from {}", uid, properties.folder(), observation.actor());
      intakes.observe(observation);
    } catch (Exception e) {
      // Including the case the payload is lazy about: with the folder left open for the uid,
      // reading the body can still fail on a connection that dropped in between.
      log.warn("Could not read a message from {}", properties.folder(), e);
    }
  }

  private static String describe(final Folder folder) {
    return folder == null ? "A closed folder" : folder.getName();
  }
}
