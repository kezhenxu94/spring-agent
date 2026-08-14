package me.kezhenxu94.springagent.cli;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the command line remembers between one prompt and the next.
 *
 * <p>Separate from {@link CliShellRunner} because the slash commands change it — {@code /clear}
 * starts a new conversation, {@code /exit} ends the loop, {@code /stop} cancels what is running —
 * and a command is a bean of its own with no way back to the runner.
 */
@Slf4j
@Component
public class CliSession {

  /**
   * Groups the turns that share chat memory. Minted at startup and replaced by {@code /clear}, so
   * one terminal session is one conversation and closing the window ends it.
   */
  private final AtomicReference<String> conversationId = new AtomicReference<>(newConversationId());

  /** The run in flight, as the id {@code SpringAgent.cancel} takes. Null between turns. */
  private final AtomicReference<String> activeRunId = new AtomicReference<>();

  private final AtomicBoolean quitting = new AtomicBoolean();

  public String conversationId() {
    return conversationId.get();
  }

  /** Forgets the conversation so far and returns the id of the new one. */
  public String clear() {
    final var id = newConversationId();
    conversationId.set(id);
    log.info("Started conversation {}", id);
    return id;
  }

  public String activeRunId() {
    return activeRunId.get();
  }

  public void runStarted(final String runId) {
    activeRunId.set(runId);
  }

  public void runEnded() {
    activeRunId.set(null);
  }

  public boolean quitting() {
    return quitting.get();
  }

  public void quit() {
    quitting.set(true);
  }

  private static String newConversationId() {
    return "cli-" + UUID.randomUUID();
  }
}
