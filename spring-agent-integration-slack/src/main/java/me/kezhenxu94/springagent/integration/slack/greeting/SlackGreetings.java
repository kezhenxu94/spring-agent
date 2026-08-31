package me.kezhenxu94.springagent.integration.slack.greeting;

import com.slack.api.methods.MethodsClient;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SeenUpdateRepo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Says hello to somebody opening the conversation, and tells somebody coming back what has changed
 * since they last looked.
 *
 * <p><b>Having no record is what makes somebody new, not which event arrived.</b> Slack's {@code
 * app_home_opened} fires every time the conversation is opened, including the first — there is no
 * separate first-contact event to read. Reading first contact off the absent row rather than off an
 * event makes that one event enough on its own, and makes a greeting survive a delivery nobody here
 * controls going missing.
 *
 * <p>What a person is shown follows from that one number:
 *
 * <ul>
 *   <li>no row — they have never been greeted, so the welcome;
 *   <li>a row behind the newest note — the notes above it, and only those;
 *   <li>a row that is level — nothing at all. The event fires on every open, and a message each
 *       time would be the agent talking over the conversation it is there to have.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackGreetings {

  private final MethodsClient slack;
  private final SlackUpdates updates;
  private final SlackGreetingBlocks blocks;
  private final SeenUpdateRepo seenUpdateRepo;
  private final ProcessedMessageRepo processedMessageRepo;

  /**
   * The event has to be acknowledged inside Slack's three seconds, and rendering plus a send is not
   * bounded by anything this code controls. Boot's general-purpose executor rather than the
   * scheduler's, whose threads exist to fire scheduled tasks on time.
   */
  @Qualifier("applicationTaskExecutor")
  private final TaskExecutor taskExecutor;

  /** Called from the event thread; returns as soon as the work is handed off. */
  public void greet(final String channelId, final String userId) {
    if (channelId == null || userId == null) {
      log.warn("An app-home event arrived naming no channel or no user; nothing to greet");
      return;
    }
    taskExecutor.execute(() -> send(channelId, userId));
  }

  private void send(final String channelId, final String userId) {
    final var current = updates.current();
    final var seen = seenUpdateRepo.findById(userId).orElse(null);
    if (seen != null && seen.version() >= current) {
      return;
    }
    // The event fires on every open, and one person opening the conversation on a phone and a
    // laptop at the same moment is two events, possibly at two replicas. Keyed on the person and
    // the version they are about to be brought up to, this is the atomic first-caller-wins that
    // already exists for exactly this shape of work. The row below is the record of what was read;
    // the claim only settles the race.
    final var claim = "slack-greeting:" + userId + ":" + current;
    if (!processedMessageRepo.claim(claim)) {
      return;
    }
    try {
      final var body =
          seen == null ? blocks.welcome() : blocks.update(updates.since(seen.version()));
      if (body.isEmpty()) {
        // A deployment shipping no notes greets nobody, which is what an empty updates directory
        // means. The claim stands: there is nothing to say at this version and nothing will change
        // that until a note is added, which bumps the version and so the claim key.
        return;
      }
      final var response =
          slack.chatPostMessage(r -> r.channel(channelId).blocks(body).text(fallback(seen)));
      if (!response.isOk()) {
        throw new IllegalStateException(
            "Could not greet " + userId + " in " + channelId + ": " + response.getError());
      }
      seenUpdateRepo.save(
          SeenUpdate.builder().id(userId).version(current).updatedAt(Instant.now()).build());
      log.info("Greeted {} in {}, now at update {}", userId, channelId, current);
    } catch (Exception e) {
      // Released, because nothing was said: holding it would leave this person permanently one
      // version behind, and every later note unread along with the one that failed.
      processedMessageRepo.release(claim);
      log.error("Failed to greet {} in {}", userId, channelId, e);
    }
  }

  private String fallback(final SeenUpdate seen) {
    final var welcome = updates.welcome();
    return seen == null && welcome != null && !welcome.title().isEmpty()
        ? welcome.title()
        : "What's new";
  }
}
