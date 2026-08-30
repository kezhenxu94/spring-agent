package me.kezhenxu94.springagent.integration.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import me.kezhenxu94.springagent.core.observing.EventIntake;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.integration.email.config.EmailProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.GenericMessage;

/**
 * The gate: which of the messages read here may go on to become a situation.
 *
 * <p>Worth a test of its own rather than left to {@code MailObservationsTest}, because an
 * observation existing and an observation being reported are now two different things, and it is
 * the second one that decides whether a stranger can put work in front of the agent.
 */
class MailObservationHandlerTest {

  private static final String VERDICT =
      "Authentication-Results: mx.example.com; dkim=pass header.d=apache.org";

  private final EmailProperties properties =
      EmailProperties.builder().host("imap.example.com").authservId("mx.example.com").build();
  private final List<Observation> reported = new ArrayList<>();
  private final MailObservationHandler handler =
      new MailObservationHandler(
          properties,
          new EventIntakes(List.<EventIntake>of(reported::add)),
          new MailObservations(properties));

  /**
   * A message that answers for its own uid, which is the one thing a mailbox gives it and an
   * in-memory one does not. Spied rather than built on a mock folder: the constructor that takes a
   * folder reads a session out of it, and a message with no session parses its own headers
   * differently.
   */
  private static MimeMessage mail(final String raw) throws Exception {
    final var message =
        spy(
            new MimeMessage(
                Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    final var folder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(((UIDFolder) folder).getUID(any())).thenReturn(7L);
    when(((UIDFolder) folder).getUIDValidity()).thenReturn(42L);
    doReturn(folder).when(message).getFolder();
    return message;
  }

  @Test
  @DisplayName("a message DKIM vouched for is reported")
  void shouldReportAVouchedMessage() throws Exception {
    final var mail = mail(VERDICT + "\nFrom: someone@apache.org\nSubject: x\n\nbody\n");

    handler.handleMessage(new GenericMessage<>(mail));

    assertThat(reported)
        .singleElement()
        .satisfies(o -> assertThat(o.actor()).isEqualTo("someone@apache.org"));
  }

  @Test
  @DisplayName("a message nobody vouched for reaches no intake")
  void shouldNotReportAnUnvouchedMessage() throws Exception {
    // The observation for it is made all the same — see MailObservationsTest — and stops here.
    // A mailbox is reachable by anybody who learns the address, so this is the whole of what
    // stands between the internet and a triage run.
    final var mail = mail("From: mallory@attacker.example\nSubject: x\n\nbody\n");

    handler.handleMessage(new GenericMessage<>(mail));

    assertThat(reported).isEmpty();
  }
}
