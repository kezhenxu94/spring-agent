package me.kezhenxu94.springagent.integration.slack.config;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Who this application is in Slack, established once at startup.
 *
 * <p><b>Resolved rather than trusted, because getting it wrong is expensive and silent.</b> The bot
 * user id is what tells a mention aimed at the agent from any other, and — far worse — what tells
 * the agent's own messages from a person's. Slack delivers what the bot posts back to the bot, so a
 * wrong id means the agent reads its own answer as a new question and answers that, for ever. That
 * is not a failure anybody wants to discover from a transcript.
 *
 * <p>So {@code auth.test} is called once here and its answer is what the rest of the module uses.
 * The configured values become a check rather than a source: where they disagree with Slack, the
 * log says so and Slack wins; where they are absent, nothing needs to be configured at all.
 *
 * <p><b>It also fails fast.</b> Without this the first symptom of a missing or stale bot token is
 * Bolt answering every single event with {@code 401 "a request for an unknown workspace detected"}
 * — which names neither the token nor the variable behind it, and which arrives once per message
 * rather than once at startup. A constructor that throws is a better place to find out.
 */
@Slf4j
@Component
public class SlackIdentity {

  /** The bot's own user id, as Slack reports it. */
  @Getter private final String botUserId;

  /** The workspace this installation belongs to, as Slack reports it. */
  @Getter private final String teamId;

  public SlackIdentity(final MethodsClient slack, final SlackProperties properties) {
    if (Strings.isNullOrEmpty(properties.botToken())) {
      throw new IllegalStateException(
          "app.slack.bot-token is not set. It is the bot user OAuth token (xoxb-...) from the"
              + " app's OAuth & Permissions page — a different credential from the app-level token"
              + " (xapp-...) that opens the Socket Mode connection. Set SLACK_BOT_TOKEN.");
    }
    final var response = authTest(slack);
    if (!response.isOk()) {
      throw new IllegalStateException(
          "Slack refused app.slack.bot-token: "
              + response.getError()
              + ". Check SLACK_BOT_TOKEN is the bot user OAuth token (xoxb-...) from this app's"
              + " OAuth & Permissions page, that the app is still installed to the workspace, and"
              + " that it was reinstalled after its scopes last changed. Note this is a different"
              + " credential from SLACK_APP_TOKEN (xapp-...), which only opens the connection —"
              + " so the socket coming up says nothing about this token being good.");
    }
    this.botUserId = response.getUserId();
    this.teamId = response.getTeamId();
    log.info(
        "Slack identity resolved: botUserId={}, teamId={}, workspace={}",
        botUserId,
        teamId,
        response.getTeam());
    warnOnDisagreement("app.slack.bot-user-id", properties.botUserId(), botUserId);
    warnOnDisagreement("app.slack.team-id", properties.teamId(), teamId);
  }

  private com.slack.api.methods.response.auth.AuthTestResponse authTest(final MethodsClient slack) {
    try {
      return slack.authTest(r -> r);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not reach Slack to check app.slack.bot-token. The agent cannot tell its own"
              + " messages from a person's until it knows its own user id, and answering its own"
              + " messages is a loop — so this is fatal rather than something to retry past.",
          e);
    }
  }

  /**
   * A configured value that disagrees with Slack is a warning rather than a failure: Slack's answer
   * is the true one and is what gets used, so the deployment works either way. It is still said out
   * loud, because a stale id here is usually the sign of a token pointed at the wrong workspace.
   */
  private static void warnOnDisagreement(
      final String property, final String configured, final String actual) {
    if (Strings.isNullOrEmpty(configured) || configured.equals(actual)) {
      return;
    }
    log.warn(
        "{} is set to {}, but Slack says {}. Using Slack's answer. This usually means the token"
            + " belongs to a different workspace than the configuration was written for.",
        property,
        configured,
        actual);
  }
}
