package me.kezhenxu94.springagent.integration.slack.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Slack workspace and application credentials.
 *
 * @param botToken the bot user's token, {@code xoxb-…}, which every Web API call is made with. It
 *     is also what a file download has to carry: Slack serves {@code url_private_download} only to
 *     a request bearing this in an {@code Authorization} header, and answers one without it with a
 *     login page rather than an error — so a missing token shows up as an HTML file in the user's
 *     workspace rather than as a failure.
 * @param appToken the app-level token, {@code xapp-…}, which opens the Socket Mode connection. A
 *     different credential from {@link #botToken} and not interchangeable with it: this one is
 *     minted per app rather than per installation and carries the {@code connections:write} scope.
 * @param botUserId the bot's own user id, {@code U…}. Load-bearing twice over, which is why it is
 *     configuration rather than something looked up: it is how a mention addressed to the agent is
 *     told from any other, and it is how the agent's own messages are told from a person's. Slack —
 *     unlike Feishu — delivers what the bot posts back to it as an ordinary message event, so
 *     without this the agent answers itself, forever.
 * @param teamId the workspace this app is installed in, {@code T…}. Reported as an {@code
 *     AgentRequest}'s tenant id, and compared against on login.
 * @param locale which language the messages speak. Defaults to the host's, so setting it is for a
 *     workspace whose language differs from the machine the agent runs on. See {@link
 *     SlackMessages} for what it selects.
 * @param requestTimeout how long any one call to Slack may take. Stated rather than left to the
 *     SDK, for the reason {@code FeishuProperties} spells out at length: a streaming write that
 *     never returns is not a lost message but a stuck run, since a subagent reports itself finished
 *     to its parent's message before the run waiting on it is released. Defaults to {@link
 *     #DEFAULT_REQUEST_TIMEOUT}.
 * @param observedChannelIds the channels whose messages are reported to {@code EventIntake} even
 *     though nobody addressed the bot in them, so that the agent can watch a conversation and later
 *     decide whether it has anything worth saying. Empty by default, and that default is the
 *     feature being off: every message in every channel the bot sits in becoming a stored row, and
 *     in time something a model is shown, is a volume and a privacy decision only whoever runs the
 *     deployment can make — naming a channel here is that decision, one channel at a time. Nothing
 *     is observed regardless where no {@code EventIntake} implementation is on the classpath. See
 *     {@code SlackChatObservations}.
 */
@ConfigurationProperties(prefix = "app.slack")
public record SlackProperties(
    String botToken,
    String appToken,
    String botUserId,
    String teamId,
    Locale locale,
    Duration requestTimeout,
    Set<String> observedChannelIds) {

  /** Generous for a message update, which is a small write to a nearby service. */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  public SlackProperties {
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    }
    if (observedChannelIds == null) {
      observedChannelIds = Set.of();
    }
  }
}
