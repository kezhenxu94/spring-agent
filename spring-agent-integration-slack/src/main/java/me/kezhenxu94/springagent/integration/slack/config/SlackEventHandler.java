package me.kezhenxu94.springagent.integration.slack.config;

import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.model.event.AppHomeOpenedEvent;
import com.slack.api.model.event.MessageEvent;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.slack.greeting.SlackGreetings;
import me.kezhenxu94.springagent.integration.slack.greeting.SlackSuggestions;
import me.kezhenxu94.springagent.integration.slack.handler.SlackMessageReceiveHandler;
import me.kezhenxu94.springagent.integration.slack.handler.SlackQuestionAnswerHandler;
import me.kezhenxu94.springagent.integration.slack.handler.SlackQuestionForm;
import me.kezhenxu94.springagent.integration.slack.handler.SlackStopButton;
import me.kezhenxu94.springagent.integration.slack.handler.SlackStopHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every Slack event this application does something with, and the connection they arrive on.
 *
 * <p>Written against Bolt, the Slack SDK's own application framework, rather than against the raw
 * Web API client. What that buys is the part a hand-rolled receiver gets subtly wrong and then has
 * to keep getting right as Slack changes: acknowledging inside the three seconds Slack allows,
 * routing an event to a typed handler, and parsing an interactive payload. What is left here is the
 * part that is actually ours — which messages are worth a run.
 *
 * <p>Bolt keeps the handlers apart from the transport, so the {@link App} below is the same object
 * a servlet deployment would register. Moving to the Events API later is {@code
 * bolt-jakarta-servlet} and a {@code ServletRegistrationBean} pointed at this bean; not one handler
 * changes, and the signature checking that path needs is Bolt's rather than ours.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SlackEventHandler {

  /**
   * Every button this application puts on a message. Bolt selects a handler by the pressed
   * element's {@code action_id}, and one pattern is what lets a question form name its inputs after
   * the question they belong to — see {@code SlackQuestionForm} for why those ids cannot be fixed.
   */
  public static final Pattern ACTION_IDS = Pattern.compile("^sa_.*");

  private final SlackProperties properties;
  private final Slack slack;
  private final SlackMessageReceiveHandler messages;
  private final SlackStopHandler stops;
  private final SlackQuestionAnswerHandler answers;
  private final SlackGreetings greetings;
  private final SlackSuggestions suggestions;

  /**
   * Forces single-workspace mode, whatever the ambient environment says.
   *
   * <p><b>{@code AppConfig}'s builder defaults read {@code System.getenv}</b> — including {@code
   * SLACK_CLIENT_ID} and {@code SLACK_CLIENT_SECRET}. Bolt calls an app with both of those set a
   * distributed app, and a distributed app authorizes each event by looking the workspace up in an
   * {@code InstallationService} rather than by using the bot token. This module has no installation
   * store and never will: it is one bot in one workspace.
   *
   * <p>That matters here because those two variables belong to something else entirely — they are
   * the Sign in with Slack credentials {@code spring-agent-app-web} uses, and a deployment running
   * both quite reasonably keeps all of its Slack settings in one file. The result was every event
   * answered with {@code 401 "a request for an unknown workspace detected"} while the bot token was
   * perfectly valid and the socket was up, which is about as misleading as a failure gets.
   *
   * <p>So they are cleared explicitly rather than merely left unset: not setting a field is not the
   * same as it being empty when the default is the environment.
   */
  static AppConfig singleWorkspace(final AppConfig config) {
    config.setClientId(null);
    config.setClientSecret(null);
    return config;
  }

  @Bean
  public App slackApp() {
    final var config =
        AppConfig.builder()
            .slack(slack)
            .singleTeamBotToken(properties.botToken())
            // Socket Mode authenticates the connection with the app-level token and delivers over
            // it, so there is no request to verify and no signing secret to verify it with. Bolt
            // installs its request-verification middleware only when one is set, so leaving this
            // unset is what says "these events did not arrive over HTTP" rather than an omission.
            .signingSecret(null)
            // Slack sends message_changed, message_deleted, channel_join and a dozen more as
            // `message` events carrying a subtype. Bolt acknowledges those itself rather than
            // handing each one to a handler that would have to recognise and drop it — so the
            // handler below sees only messages somebody actually typed.
            .subtypedMessageEventsAutoAckEnabled(true)
            .build();

    final var app = new App(singleWorkspace(config));

    // A plain message, in a channel or a direct message. Deliberately NOT also app_mention: Slack
    // delivers a message that mentions the bot under both events, with a different event id each
    // time, so subscribing to both means every mention is handled twice and the delivery claim
    // cannot tell that it is the same message. Whether the bot was addressed is decided from the
    // text instead, which is one source of truth rather than two.
    app.event(MessageEvent.class, messages::onMessage);

    // Somebody opening the conversation with the bot, which is where a greeting goes. The
    // `messages` tab is the direct message; the `home` tab is a different surface this application
    // does not publish, and an event for it is nothing to act on.
    app.event(
        AppHomeOpenedEvent.class,
        (payload, ctx) -> {
          final var opened = payload.getEvent();
          // The `messages` tab is the direct message with the bot, which is the conversation a
          // greeting belongs in. The `home` tab is a different surface this application does not
          // publish, and an event for it is nothing to act on.
          if ("messages".equals(opened.getTab())) {
            greetings.greet(opened.getChannel(), opened.getUser());
          }
          return ctx.ack();
        });

    // The two buttons this application draws. Bolt selects by action_id, so these two lines are the
    // whole of the coupling between what the updater renders and what answers a press.
    app.blockAction(SlackStopButton.ACTION_ID, stops::handle);
    app.blockAction(SlackQuestionForm.ACTION_ID, answers::handle);
    app.blockAction(SlackSuggestions.ACTION_ID, suggestions::handle);

    return app;
  }

  /**
   * The connection itself, started once the context is up and closed with it.
   *
   * <p>{@code startAsync} rather than {@code start}: the latter blocks the calling thread for the
   * life of the connection, and that thread is the one finishing Spring's startup.
   */
  @Bean(initMethod = "startAsync", destroyMethod = "close")
  public SocketModeApp slackSocketModeApp(final App app) throws Exception {
    return new SocketModeApp(properties.appToken(), app);
  }
}
