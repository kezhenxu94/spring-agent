package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.model.event.MessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.integration.slack.config.SlackIdentity;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import org.springframework.stereotype.Component;

/**
 * Reports a channel message the bot was not addressed in, so that the agent can watch a
 * conversation it is not part of and later decide whether it has anything worth saying.
 *
 * <p>A class of its own rather than more of {@link SlackMessageReceiveHandler}, which is about
 * answering a message. This is the opposite case — the message that is not being answered — and
 * keeping it apart is what makes it obvious at the call site that nothing here starts a run.
 *
 * <p>Reported to {@link EventIntakes}, so this module keeps its compile dependency on core alone
 * and knows nothing about who is listening: {@code spring-agent-events} turns these into
 * situations, and an application that wants something else done with them adds an intake of its own
 * and gets it. A deployment with no intake at all simply observes nothing, which is the default
 * rather than a degraded mode.
 *
 * <p>What it promises the funnel is at-least-once with a stable delivery id, and no more than that.
 * Slack redelivers an event it has not heard the acknowledgement for and a reconnecting Socket Mode
 * connection replays one, so the same message can be reported twice; the event id it is reported
 * under does not change between those attempts, which is the whole of what a transport has to get
 * right. Recognising the second attempt for what it is belongs to {@code EventIntake}, which does
 * it once for every source rather than once per transport.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackChatObservations {

  /**
   * The name a deployment configures policy for these observations under, and the same literal as
   * {@code EventsProperties.SLACK_CHAT} in {@code spring-agent-events}.
   *
   * <p>Textually coupled rather than shared, because the dependency may only point from a transport
   * to core and this is not core's business to name. Change one and the other stops selecting the
   * settings it was written for, silently, so change both.
   */
  public static final String SOURCE = "slack-chat";

  private final SlackProperties properties;

  private final SlackIdentity identity;

  /**
   * The words this class puts around what was said. They reach the model inside the brief a triage
   * run is given, so they are the agent's own text and are translated like the rest of it.
   */
  private final SlackMessages messages;

  private final SlackUserNames userNames;

  private final EventIntakes eventIntakes;

  /**
   * Reports {@code event} as something seen in the channel it came from, if that channel is watched
   * at all.
   *
   * <p>Never throws. This is called on the path that acknowledges the message: an exception
   * escaping here would leave the delivery unacknowledged and Slack would send the same message
   * again — so a funnel that is broken, slow to fail, or absent costs an observation and nothing
   * else.
   */
  public void observed(final MessageEvent event, final String deliveryId) {
    try {
      if (eventIntakes.isEmpty()) {
        // Nothing would be done with it, and reading the message out of the event is not free.
        return;
      }

      final var channelId = event.getChannel();
      // Every message in every channel the bot sits in would otherwise become a stored row and, in
      // time, something shown to a model — a volume and a privacy decision that belongs to whoever
      // runs the deployment, so watching is off until a channel is named. Checked before anything
      // leaves this thread, so that an unwatched channel leaves no trace anywhere.
      if (channelId == null || !properties.observedChannelIds().contains(channelId)) {
        return;
      }

      // Only what the message itself says. Deliberately not SlackMessageText, which turns a file
      // into text by downloading it into a user's workspace — seconds of work and a write to disk
      // for a message nobody asked about, and it would happen on this very thread, where Slack is
      // waiting for the acknowledgement. A message carrying no text is therefore not observed at
      // all rather than observed as the bare fact that something arrived, which no run could act
      // on.
      final var text = userNames.resolve(event.getText());
      if (Strings.isNullOrEmpty(text)) {
        return;
      }

      final var speaker = userNames.nameOf(event.getUser());

      eventIntakes.observe(
          Observation.builder()
              .source(SOURCE)
              // Slack's own event id, unprefixed. It is stable across a redelivery of the same
              // message and different for the next one, which is what the funnel needs of a
              // delivery id, and the funnel namespaces it by source when it claims one — so a
              // prefix here would only spell the source twice.
              .deliveryId(deliveryId)
              .kind("chat.message")
              // One rolling window per channel, not per topic. Deciding that two messages are about
              // the same thing would take embeddings and a threshold, and would be wrong often
              // enough to split a conversation in half; a channel is a grouping that is right by
              // construction, and how much of it is worth reasoning about is a question for
              // whatever reads the window later.
              .correlationKey(SOURCE + ":" + channelId)
              .title(messages.get("chat-observation-title", channelId))
              // Who spoke belongs here: Observation has no field for it on purpose, because the
              // speaker is evidence and must never be mistaken for the identity a run about this
              // acts as.
              .summary(messages.get("chat-observation-said", speaker, text))
              .payloadJson(null)
              .route(
                  Route.builder()
                      .chatId(channelId)
                      .chatType(event.getChannelType())
                      .groupId(channelId)
                      .tenantId(
                          Strings.isNullOrEmpty(event.getTeam())
                              ? identity.teamId()
                              : event.getTeam())
                      .build())
              .build());
    } catch (Exception e) {
      log.warn("Failed to observe message {} in channel {}", event.getTs(), event.getChannel(), e);
    }
  }
}
