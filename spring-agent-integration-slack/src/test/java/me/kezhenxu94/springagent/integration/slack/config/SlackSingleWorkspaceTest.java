package me.kezhenxu94.springagent.integration.slack.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.bolt.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That this module stays a single-workspace bot whatever else is in the environment.
 *
 * <p>{@code AppConfig}'s builder defaults read {@code System.getenv}, so {@code SLACK_CLIENT_ID}
 * and {@code SLACK_CLIENT_SECRET} — which belong to the browser surface's Sign in with Slack, not
 * to the bot — turn this into a distributed app that authorizes events against an installation
 * store this module does not have. The symptom is every event refused with {@code 401 "a request
 * for an unknown workspace detected"} while the bot token is valid and the socket is up.
 */
class SlackSingleWorkspaceTest {

  @Test
  @DisplayName(
      "OAuth credentials picked up from the environment do not make this a distributed app")
  void shouldStaySingleWorkspace() {
    // Exactly what the builder produces when SLACK_CLIENT_ID and SLACK_CLIENT_SECRET are exported,
    // which is what happens the moment somebody sources a .env holding the web surface's settings.
    final var polluted =
        AppConfig.builder()
            .singleTeamBotToken("xoxb-real")
            .clientId("12345.67890")
            .clientSecret("secret")
            .build();

    assertThat(polluted.isDistributedApp()).as("precondition").isTrue();

    assertThat(SlackEventHandler.singleWorkspace(polluted).isDistributedApp()).isFalse();
  }

  @Test
  @DisplayName("and the bot token is left alone, since that is what authorizes every event")
  void shouldKeepTheBotToken() {
    final var config =
        AppConfig.builder().singleTeamBotToken("xoxb-real").clientId("a").clientSecret("b").build();

    assertThat(SlackEventHandler.singleWorkspace(config).getSingleTeamBotToken())
        .isEqualTo("xoxb-real");
  }
}
