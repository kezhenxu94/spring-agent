package me.kezhenxu94.springagent.integration.slack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.auth.AuthTestResponse;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a bad bot token is found at startup rather than once per delivered event.
 *
 * <p>Without this check the first symptom is Bolt answering every event with {@code 401 "a request
 * for an unknown workspace detected"}, which names neither the token nor the variable behind it.
 */
class SlackIdentityTest {

  private static SlackProperties properties(final String botToken, final String botUserId) {
    return new SlackProperties(botToken, "xapp", botUserId, "T0TEAM", Locale.ENGLISH, null, null);
  }

  private static MethodsClient answering(final AuthTestResponse response) {
    final var slack = mock(MethodsClient.class);
    try {
      when(slack.authTest(any(com.slack.api.RequestConfigurator.class))).thenReturn(response);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return slack;
  }

  private static AuthTestResponse ok(final String userId, final String teamId) {
    final var response = new AuthTestResponse();
    response.setOk(true);
    response.setUserId(userId);
    response.setTeamId(teamId);
    return response;
  }

  @Test
  @DisplayName("the identity Slack reports is the one used")
  void shouldResolveFromSlack() {
    final var identity =
        new SlackIdentity(answering(ok("U0REAL", "T0REAL")), properties("xoxb", "U0REAL"));

    assertThat(identity.botUserId()).isEqualTo("U0REAL");
    assertThat(identity.teamId()).isEqualTo("T0REAL");
  }

  @Test
  @DisplayName("Slack wins over a configured id that disagrees, since answering itself is a loop")
  void shouldPreferSlackOverConfiguration() {
    final var identity =
        new SlackIdentity(answering(ok("U0REAL", "T0REAL")), properties("xoxb", "U0STALE"));

    assertThat(identity.botUserId()).isEqualTo("U0REAL");
  }

  @Test
  @DisplayName("an unset bot token is refused by name, not left to fail per event")
  void shouldRefuseAMissingToken() {
    assertThatThrownBy(() -> new SlackIdentity(mock(MethodsClient.class), properties("", "U0")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.slack.bot-token")
        .hasMessageContaining("SLACK_BOT_TOKEN");
  }

  @Test
  @DisplayName("a token Slack rejects says so, and says which credential it is not")
  void shouldRefuseARejectedToken() {
    final var refused = new AuthTestResponse();
    refused.setOk(false);
    refused.setError("invalid_auth");

    assertThatThrownBy(() -> new SlackIdentity(answering(refused), properties("xoxb-bad", "U0")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid_auth")
        // The distinction that actually unblocks somebody: the socket coming up proves the app
        // token, not this one.
        .hasMessageContaining("SLACK_APP_TOKEN");
  }
}
