package me.kezhenxu94.springagent.integration.telegram.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bot, and who may talk to it.
 *
 * @param botToken the token BotFather issued, {@code 123456:ABC-…}. It is the whole of the bot's
 *     identity and it is part of every request path, which is why nothing in this module ever logs
 *     a request URL — see {@code TelegramApiException}.
 * @param apiUrl where the Bot API is served. Telegram's own unless a deployment runs the local Bot
 *     API server, which lifts the 20 MB download limit.
 * @param locale which language the messages speak. Defaults to the host's — see {@link
 *     TelegramMessages}.
 * @param requestTimeout how long any one call to Telegram may take, for the reason {@code
 *     app.slack.request-timeout} is stated: a write that never returns is a stuck run.
 * @param pollTimeout how long one long poll waits for something to happen. Telegram allows up to 50
 *     seconds; the request's own read timeout is this plus {@link #requestTimeout}.
 * @param allowedUserIds the people who may talk to the bot, by Telegram user id. <b>A Telegram bot
 *     is reachable by anybody on Telegram who knows its name</b> — there is no workspace around it
 *     the way there is around a Slack or Feishu bot — and a run acts with the files, credentials
 *     and MCP servers of whoever it is for, and spends this deployment's money. So there is no
 *     permissive default: empty here and in {@link #allowedChatIds} admits nobody, the way {@code
 *     app.web.auth.tenant-id} does.
 * @param allowedChatIds the groups whose every member may talk to the bot, by chat id (negative,
 *     {@code -100…} for a supergroup). Admitting a group admits whoever its admins let in.
 * @param observedChatIds the groups whose messages are reported to {@code EventIntake} though
 *     nobody addressed the bot — see {@code app.slack.observed-channel-ids}, which this is the same
 *     decision as. Needs the bot's privacy mode off, or Telegram never delivers those messages.
 * @param streamInterval the least time between two edits of a reply. Telegram allows a chat about
 *     one message a second and a group twenty a minute, and answers a burst with 429 — so this is a
 *     floor, and the default suits a group.
 */
@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
    String botToken,
    String apiUrl,
    Locale locale,
    Duration requestTimeout,
    Duration pollTimeout,
    Set<String> allowedUserIds,
    Set<String> allowedChatIds,
    Set<String> observedChatIds,
    Duration streamInterval) {

  public static final String DEFAULT_API_URL = "https://api.telegram.org";

  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  public static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(25);

  public static final Duration DEFAULT_STREAM_INTERVAL = Duration.ofSeconds(3);

  public TelegramProperties {
    apiUrl = apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl;
    requestTimeout = positive(requestTimeout, DEFAULT_REQUEST_TIMEOUT);
    pollTimeout = positive(pollTimeout, DEFAULT_POLL_TIMEOUT);
    streamInterval = streamInterval == null ? DEFAULT_STREAM_INTERVAL : streamInterval;
    allowedUserIds = allowedUserIds == null ? Set.of() : allowedUserIds;
    allowedChatIds = allowedChatIds == null ? Set.of() : allowedChatIds;
    observedChatIds = observedChatIds == null ? Set.of() : observedChatIds;
  }

  private static Duration positive(final Duration value, final Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
