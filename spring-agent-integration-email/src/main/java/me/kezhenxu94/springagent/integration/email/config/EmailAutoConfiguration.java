package me.kezhenxu94.springagent.integration.email.config;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.events.config.EventsAutoConfiguration;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import me.kezhenxu94.springagent.integration.email.MailObservationHandler;
import me.kezhenxu94.springagent.integration.email.MailObservations;
import me.kezhenxu94.springagent.integration.email.aot.EmailRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mail.inbound.ImapIdleChannelAdapter;
import org.springframework.integration.mail.inbound.ImapMailReceiver;

/**
 * Offers a watched mailbox as something the agent can be told about.
 *
 * <p>What this module contributes is a connection and a reading of what arrives on it. Everything
 * after that — recording it, correlating it with what came before, deciding whether any of it is
 * worth waking the agent for — belongs to {@code spring-agent-events} and is the same whatever the
 * source.
 *
 * <p><b>Two switches, and both have to be on.</b> {@code app.events.enabled} puts the machinery
 * there that makes anything of an observation, and this module is conditional on the properties
 * bean that switch creates; {@code app.email.enabled} is this module's own, because unlike the
 * webhook sources — which contribute a reader to an endpoint somebody else already opened — taking
 * this module on the classpath is not free. It dials out, holds a connection, and logs into a
 * mailbox, so it is a decision a deployment makes rather than one the classpath makes for it.
 */
@Slf4j
@AutoConfiguration(after = EventsAutoConfiguration.class)
@ConditionalOnProperty(prefix = EmailProperties.PREFIX, name = "enabled", havingValue = "true")
@ConditionalOnBean(EventsProperties.class)
@EnableConfigurationProperties(EmailProperties.class)
@ImportRuntimeHints(EmailRuntimeHints.class)
public class EmailAutoConfiguration {

  /**
   * Refuses to start where this source would accept mail from anybody.
   *
   * <p>The one place this module departs from every other source, which take the permissive default
   * and are merely complained about at startup. That default exists so a deployment upgrading into
   * the feature does not silently stop triaging what it was already triaging — a repository webhook
   * whose events reach it through a signature and a secret. It cannot be right here: nobody
   * upgrades into a mailbox, an address is reachable by anyone who learns it, and "everybody" for a
   * mailbox means the internet. So this one is checked rather than mentioned.
   *
   * <p>A bean rather than a check inside another, so that it fails at context refresh with the
   * property name in the message, before a connection has been opened to anything.
   */
  @Bean
  EmailSourceCheck emailSourceCheck(
      final EmailProperties properties, final EventsProperties events) {
    return new EmailSourceCheck(properties, events);
  }

  /** Marker for the startup check above; holds nothing and does its work in its constructor. */
  public static final class EmailSourceCheck {

    EmailSourceCheck(final EmailProperties properties, final EventsProperties events) {
      if (properties.host() == null || properties.host().isBlank()) {
        throw new IllegalStateException(
            "app.email.enabled is true but app.email.host is not set; there is no mailbox to"
                + " watch");
      }
      if (properties.authservId() == null || properties.authservId().isBlank()) {
        throw new IllegalStateException(
            "app.email.authserv-id is not set, so no sender could ever be authenticated and every"
                + " message would be dropped for having no actor. It must name the mail server"
                + " that verifies DKIM for this mailbox, as that server names itself in the"
                + " Authentication-Results header it adds.");
      }
      final var policy = events.policyFor(EmailProperties.SOURCE);
      if (policy.isEmpty()) {
        throw new IllegalStateException(
            "app.email.enabled is true but app.events.sources."
                + EmailProperties.SOURCE
                + " is not configured, so nothing would become of any mail read here");
      }
      if (policy.get().trustedActors() == null) {
        throw new IllegalStateException(
            "app.events.sources."
                + EmailProperties.SOURCE
                + ".trusted-actors is not set. Unlike a signed webhook, a mailbox is reachable by"
                + " anybody who learns the address, so this source will not run without naming"
                + " whose mail it accepts — for example ['.+@example\\.com'].");
      }
    }
  }

  @Bean
  @ConditionalOnMissingBean
  MailObservations mailObservations(final EmailProperties properties) {
    return new MailObservations(properties);
  }

  /**
   * Where the adapter puts what it fetched, and the handler that reads it.
   *
   * <p>A direct channel, so the handler runs on the adapter's own thread and a message is not
   * considered handled until it has been. That ordering is what makes the adapter's flagging
   * at-least-once rather than at-most-once: a crash between fetching and reporting leaves the
   * message unflagged and it is read again, and the duplicate is collapsed by the funnel, which
   * recognises it by delivery id.
   */
  @Bean
  DirectChannel emailMailChannel() {
    return new DirectChannel();
  }

  @Bean
  MailObservationHandler emailMailHandler(
      final EmailProperties properties,
      final EventIntakes intakes,
      final MailObservations observations,
      final DirectChannel emailMailChannel) {
    final var handler = new MailObservationHandler(properties, intakes, observations);
    emailMailChannel.subscribe(handler);
    return handler;
  }

  @Bean
  @ConditionalOnMissingBean
  ImapMailReceiver emailMailReceiver(final EmailProperties properties) {
    final var receiver = new ImapMailReceiver(properties.storeUri());

    final var mail = new Properties();
    mail.setProperty("mail.imaps.ssl.enable", "true");
    // Refuse a server that cannot prove it is the one configured. Without it the password and every
    // message are readable by whoever answered the connection.
    mail.setProperty("mail.imaps.ssl.checkserveridentity", "true");
    receiver.setJavaMailProperties(mail);

    // Credentials through the authenticator rather than in the store URI. A URI carries them as
    // "imaps://user:password@host", which means percent-encoding a password nobody chose with a URI
    // in mind — and a password containing an @ or a / then either fails to parse or, worse, parses
    // into a different host.
    receiver.setJavaMailAuthenticator(
        new Authenticator() {
          @Override
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(properties.username(), properties.password());
          }
        });

    // Read, never write. Nothing here deletes anything, and the flag it marks progress with is its
    // own rather than \Seen — see EmailProperties#userFlag.
    receiver.setShouldDeleteMessages(false);
    receiver.setShouldMarkMessagesAsRead(false);
    receiver.setUserFlag(properties.userFlag());
    receiver.setMaxFetchSize(properties.maxFetchSize());
    // The folder is left open so the handler can ask it for a message's uid, which is what the
    // delivery id is made of and the one thing a closed folder cannot answer.
    receiver.setAutoCloseFolder(false);
    // Cancel and reissue IDLE on a timer, so a connection that died without being closed is found
    // by us rather than waited on for ever. See EmailProperties#cancelIdleInterval.
    receiver.setCancelIdleInterval(properties.cancelIdleInterval().toMillis());
    return receiver;
  }

  @Bean
  @ConditionalOnMissingBean
  ImapIdleChannelAdapter emailIdleAdapter(
      final ImapMailReceiver emailMailReceiver,
      final DirectChannel emailMailChannel,
      final EmailProperties properties,
      final EventsProperties events) {
    final var adapter = new ImapIdleChannelAdapter(emailMailReceiver);
    adapter.setOutputChannel(emailMailChannel);
    adapter.setShouldReconnectAutomatically(true);
    adapter.setReconnectDelay(properties.reconnectDelay().toMillis());
    // Everything a misconfiguration would turn on, in one line, because the failure this feature
    // has is silence: a wrong authserv-id, a folder nobody writes to, and a trusted-actors list
    // nobody matches all look identical from outside, and each is visible here at a glance.
    log.info(
        "Watching {} on {}:{} as {}, trusting what '{}' says about a sender, accepting {}",
        properties.folder(),
        properties.host(),
        properties.port(),
        properties.username(),
        properties.authservId(),
        events
            .policyFor(EmailProperties.SOURCE)
            .map(policy -> policy.trustedActors())
            .orElse(null));
    return adapter;
  }
}
