package me.kezhenxu94.springagent.core.agent;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * What arrived for a run while it was already working, waiting for a point at which the run can
 * read it.
 *
 * <p>A turn is a loop of model calls and tool calls, and the message history it builds is a
 * sequence the provider will reject if it is disturbed: an assistant message with tool calls has to
 * be followed by their results and nothing else. So a message that arrives mid-run waits here until
 * the turn is between iterations — every tool call answered, nothing outstanding — and is added to
 * the history there, as the user message it is. {@code InterceptingToolCallingManager} is what
 * reads this, at exactly that point; the run reaches it through its tool context.
 *
 * <p>One per run, closed when the run ends. Whatever is still in it then was never read, and {@link
 * SpringAgent} answers it as a run of its own — which is also what becomes of a message that
 * arrives when there is no run to hand it to.
 */
@Slf4j
public final class QueuedMessages {

  /**
   * One message, and the request that would have answered it had nothing been going. The text is a
   * supplier because producing it can mean downloading whatever the message carries, which is not
   * work to do until it is known that the message will be read at all — and not on the thread that
   * received it, which has a delivery deadline.
   */
  record Queued(AgentRequest request, Supplier<String> message) {}

  private final Queue<Queued> waiting = new ConcurrentLinkedQueue<>();

  /** Said out loud once per read, so a surface can show that the message landed. */
  private final Runnable onRead;

  private boolean open = true;

  QueuedMessages(final Runnable onRead) {
    this.onRead = onRead;
  }

  /** Queues {@code message}, or refuses it because the run can no longer read one. */
  synchronized boolean offer(final Queued message) {
    if (!open) {
      return false;
    }
    waiting.add(message);
    return true;
  }

  /**
   * Everything queued so far, as the text of the messages to add to the turn, leaving the queue
   * empty.
   *
   * <p>A message whose text cannot be produced is dropped with a warning rather than failing the
   * tool call it was read from: the call has already done its work, and the run is worth more than
   * the message.
   */
  public synchronized List<String> read() {
    if (waiting.isEmpty()) {
      return List.of();
    }
    final var texts = new ArrayList<String>();
    for (Queued queued = waiting.poll(); queued != null; queued = waiting.poll()) {
      try {
        final var text = queued.message().get();
        if (!Strings.isNullOrEmpty(text)) {
          texts.add(text);
        }
      } catch (Exception e) {
        log.warn("Could not read a message queued onto a running run; dropping it", e);
      }
    }
    if (!texts.isEmpty()) {
      onRead.run();
    }
    return texts;
  }

  /**
   * Takes the queue out of use and hands back what was never read, for whoever closed it to answer
   * some other way. Synchronized with {@link #read()}, so a message is either read into the run or
   * handed back here, never both and never neither.
   */
  synchronized List<Queued> close() {
    open = false;
    final var unread = new ArrayList<Queued>();
    for (Queued queued = waiting.poll(); queued != null; queued = waiting.poll()) {
      unread.add(queued);
    }
    return unread;
  }
}
