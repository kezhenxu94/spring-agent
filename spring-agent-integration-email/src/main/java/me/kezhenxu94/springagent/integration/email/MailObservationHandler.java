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
      if (observation.isEmpty()) {
        // Why is said by SenderIdentity, which has the detail. This says that a message was here
        // at all, so that "nothing happened" can be told apart from "nothing arrived" — the two
        // look identical from outside and want entirely different things looked at.
        log.info("Read message {} in {}, and reported nothing", uid, properties.folder());
        return;
      }
      // At info because the whole point of the source is that this happened, and because it is one
      // line per message rather than per poll: a mailbox busy enough for this to be noise is one
      // busy enough that the situations it opens are the noise instead.
      log.info(
          "Reporting message {} in {} from {}",
          uid,
          properties.folder(),
          observation.get().actor());
      intakes.observe(observation.get());
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
