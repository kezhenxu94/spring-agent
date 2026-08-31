package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the agent puts on a message it cannot answer yet.
 *
 * <p>A message sent while a run is already working is queued onto that run rather than answered by
 * one of its own, and until the run reaches a point where it can read it, nothing happens: the
 * reply above goes on streaming an answer to the previous message, and the person who just typed
 * has no way to tell their message was seen at all, or whether they should send it again. The reply
 * does say so, but the reply is a different message from theirs, and on a phone it is often not the
 * one on screen. A reaction is on the message itself, which is where they are looking.
 *
 * <p>Two of them, because there are two things worth knowing and they happen at different times:
 * {@code eyes} the moment the message is queued, and {@code white_check_mark} when the run has
 * actually taken it in. The first is left in place rather than removed — between them they read as
 * a progression, and a message still showing only {@code eyes} when the run ends is a true
 * statement about what happened to it.
 *
 * <p>Failures are logged and dropped. A reaction is a courtesy on top of the run, and a run worth
 * less than its own decoration would be a poor trade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackMessageReactions {

  /** Seen, and waiting for the run to reach a point where it can read it. */
  private static final String QUEUED = "eyes";

  /** Read into the run, so the model is working with it now. */
  private static final String READ = "white_check_mark";

  private final MethodsClient slack;

  /**
   * Which channel each message is in.
   *
   * <p>Slack identifies a message by channel <em>and</em> timestamp, but a run reports a queued
   * message by request id alone — which here is the timestamp. So the channel has to be remembered
   * when the message is received, or there is nothing to react in. In memory, and exactly as
   * durable as the thing it feeds: a reaction missed across a restart is a courtesy missed.
   */
  private final ConcurrentMap<String, String> channels = new ConcurrentHashMap<>();

  /** Remembers where {@code ts} lives, so a later reaction knows which channel to look in. */
  public void track(final String ts, final String channelId) {
    if (!Strings.isNullOrEmpty(ts) && !Strings.isNullOrEmpty(channelId)) {
      channels.put(ts, channelId);
    }
  }

  /** Marks {@code ts} as seen. */
  public void queued(final String ts) {
    react(ts, QUEUED);
  }

  /** Marks {@code ts} as taken in by the run, and forgets where it was. */
  public void read(final String ts) {
    react(ts, READ);
    channels.remove(ts);
  }

  private void react(final String ts, final String emoji) {
    final var channelId = channels.get(ts);
    if (Strings.isNullOrEmpty(ts) || Strings.isNullOrEmpty(channelId)) {
      return;
    }
    try {
      final var response = slack.reactionsAdd(r -> r.channel(channelId).timestamp(ts).name(emoji));
      // already_reacted is not a failure: a redelivery reacting twice is the claim working, not
      // something to log as broken.
      if (!response.isOk() && !"already_reacted".equals(response.getError())) {
        log.warn("Failed to react {} to {} in {}: {}", emoji, ts, channelId, response.getError());
      }
    } catch (Exception e) {
      log.warn("Failed to react {} to {} in {}", emoji, ts, channelId, e);
    }
  }
}
