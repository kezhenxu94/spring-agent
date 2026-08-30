package me.kezhenxu94.springagent.integration.email.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The mailbox to watch and how to tell who wrote to it.
 *
 * <p>Every default here is stated in the compact constructor as well as in the application's {@code
 * application.yaml}, for the reason {@code EventsProperties} gives: both read the same constant, so
 * a properties object built in a test is as complete as one Boot bound.
 *
 * @param enabled whether any of this runs. Off by default, and a real flag rather than a check on
 *     whether a host was configured: conditions are evaluated against raw property values, and a
 *     credential is a {@code ${PLACEHOLDER}} that fails to resolve precisely when the feature is
 *     not set up. It opens an outbound connection and holds it, so it is a deployment's decision.
 * @param host the IMAP server. No default; there is no sensible one.
 * @param port 993, the IMAPS port.
 * @param username the mailbox to log in as
 * @param password its password, or an app-specific token where the provider issues those
 * @param folder which folder to watch, {@code INBOX} unless a deployment sorts its mail first
 * @param authservId the {@code authserv-id} this deployment's own mail server stamps its {@code
 *     Authentication-Results} headers with, and the whole of what makes a sender's address an
 *     identity rather than a string. No default: a wrong guess would silently authenticate nobody,
 *     and a blank one is refused at startup. See {@link
 *     me.kezhenxu94.springagent.integration.email.AuthenticationResults} for what it has to be able
 *     to assume about that server.
 * @param userFlag the IMAP keyword marking a message this has already reported. A flag of the
 *     agent's own rather than {@code \Seen}, so that its progress through the mailbox is not the
 *     same bit a person toggles by opening a message — and so that a person reading the mailbox
 *     cannot make the agent skip mail by accident.
 * @param maxFetchSize how many messages to take in one pass. What keeps a backlog — a first start
 *     against a mailbox with a thousand messages in it — from becoming a thousand situations at
 *     once; the rest are taken on the next pass.
 * @param maxBodyLength how much of a message is kept as evidence. A mail body has no useful upper
 *     bound and the situation it lands in stores a bounded number of them.
 * @param cancelIdleInterval how often to cancel and reissue the idling read. IMAP IDLE blocks until
 *     the server says something, so a connection that has died without being closed looks exactly
 *     like a quiet mailbox — for ever. Under the RFC's twenty-nine minutes, and low enough that a
 *     silently dead connection is noticed within one interval rather than at the next delivery
 *     nobody gets.
 * @param reconnectDelay how long to wait before reconnecting after a failure. A server that is down
 *     stays down for minutes, and a tight loop against it is how a mailbox gets its account locked.
 */
@ConfigurationProperties(prefix = EmailProperties.PREFIX)
@lombok.Builder
public record EmailProperties(
    boolean enabled,
    String host,
    Integer port,
    String username,
    String password,
    String folder,
    String authservId,
    String userFlag,
    Integer maxFetchSize,
    Integer maxBodyLength,
    Duration cancelIdleInterval,
    Duration reconnectDelay) {

  public static final String PREFIX = "app.email";

  /**
   * The name this source is known by: the key its policy lives under in {@code app.events.sources},
   * and what an {@code Observation} reports as its source.
   *
   * <p>Spelled again in {@code application.yaml} and in the prompt file name {@code
   * events/prompts/email-triage-prompt.md}, which {@code TriagePrompts} resolves by convention.
   * Renaming this without renaming those leaves the source silently unconfigured and reading the
   * generic prompt.
   */
  public static final String SOURCE = "email";

  public static final int DEFAULT_PORT = 993;
  public static final String DEFAULT_FOLDER = "INBOX";
  public static final String DEFAULT_USER_FLAG = "spring-agent-seen";
  public static final int DEFAULT_MAX_FETCH_SIZE = 50;
  public static final int DEFAULT_MAX_BODY_LENGTH = 8000;
  public static final Duration DEFAULT_CANCEL_IDLE_INTERVAL = Duration.ofMinutes(9);
  public static final Duration DEFAULT_RECONNECT_DELAY = Duration.ofSeconds(30);

  public EmailProperties {
    port = port == null || port <= 0 ? DEFAULT_PORT : port;
    folder = blankTo(folder, DEFAULT_FOLDER);
    userFlag = blankTo(userFlag, DEFAULT_USER_FLAG);
    maxFetchSize =
        maxFetchSize == null || maxFetchSize <= 0 ? DEFAULT_MAX_FETCH_SIZE : maxFetchSize;
    maxBodyLength =
        maxBodyLength == null || maxBodyLength <= 0 ? DEFAULT_MAX_BODY_LENGTH : maxBodyLength;
    cancelIdleInterval = positive(cancelIdleInterval, DEFAULT_CANCEL_IDLE_INTERVAL);
    reconnectDelay = positive(reconnectDelay, DEFAULT_RECONNECT_DELAY);
  }

  /** The store URI, without credentials. See {@code EmailAutoConfiguration} for why not. */
  public String storeUri() {
    return "imaps://" + host + ":" + port + "/" + folder;
  }

  private static String blankTo(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
