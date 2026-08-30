package me.kezhenxu94.springagent.appweb.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;

/**
 * Everything one run has emitted, in order, so a browser can join it late.
 *
 * <p>This is what makes the stream a side effect rather than the run. A run lives in {@code
 * SpringAgent} and reports through listeners; nothing about it is tied to a caller, and a browser
 * that goes away — a refresh, a closed tab, a laptop lid — must not be able to disturb it. So the
 * listener writes here, and an HTTP connection is only ever a reader that may or may not be
 * present. A reader that comes back says how far it got, gets the rest at once, and carries on
 * live.
 *
 * <p>The handover from replay to live is the part worth being careful about: a naive
 * "send-history-then-subscribe" loses whatever arrives between the two, and a naive
 * "subscribe-then-send-history" sends some of it twice. {@link #attach} therefore takes the lock
 * that {@link #append} holds, so the reader is added to {@link #readers} at a point where the event
 * list cannot move underneath it.
 */
@Slf4j
public class RunJournal {

  /** Told about each event as it lands. The SSE endpoint's side of the connection. */
  public interface Reader {
    void onEvent(RunEvent event);

    void onClosed();
  }

  @Getter private final String requestId;
  @Getter private final String conversationId;
  @Getter private final String userId;

  /**
   * Guards {@link #events}, {@link #readers} and {@link #outcome} together. One lock rather than
   * three because the invariant they hold is a joint one — a reader is attached at a known position
   * in the event list.
   */
  private final Object lock = new Object();

  private final List<RunEvent> events = new ArrayList<>();
  private final List<Reader> readers = new CopyOnWriteArrayList<>();

  /** Null while the run is going. Set exactly once, by {@code onFinished}. */
  @Getter private volatile AgentOutcome outcome;

  /** When the run ended, so {@link RunJournals} knows when this may be evicted. */
  @Getter private volatile Instant finishedAt;

  public RunJournal(final String requestId, final String conversationId, final String userId) {
    this.requestId = requestId;
    this.conversationId = conversationId;
    this.userId = userId;
  }

  public boolean live() {
    return outcome == null;
  }

  /** Records an event and hands it to whoever is watching. */
  public void append(final RunEvent event) {
    final RunEvent stamped;
    synchronized (lock) {
      stamped = event.at(events.size() + 1L);
      events.add(stamped);
      if (RunEvent.FINISHED.equals(stamped.type())) {
        finishedAt = Instant.now();
      }
    }
    for (final var reader : readers) {
      try {
        reader.onEvent(stamped);
      } catch (final Exception e) {
        // A reader that has gone away is the ordinary case, not a failure: the browser closed the
        // tab. Drop it and carry on — the run is not the connection's business.
        log.debug("Dropping a reader of run {}: {}", requestId, e.toString());
        detach(reader);
      }
    }
  }

  void finish(final AgentOutcome ended) {
    this.outcome = ended == null ? AgentOutcome.COMPLETED : ended;
  }

  /**
   * Sends {@code reader} everything after {@code from} and leaves it attached for what comes next.
   *
   * @param from the last sequence number the reader already has; 0 for a reader starting fresh
   * @return false if the run has already finished and everything has been replayed, so the caller
   *     can close the connection rather than hold it open for a run that will never speak again
   */
  public boolean attach(final Reader reader, final long from) {
    synchronized (lock) {
      // Replayed under the lock, not merely copied under it. Attaching first and replaying after
      // would let an event appended in between overtake the backlog and reach the browser out of
      // order, which for a content delta means text that arrives scrambled. The cost is that an
      // append waits for one reader's catch-up; that write goes into the servlet's output buffer
      // rather than to the network, and a reader that has actually gone away throws rather than
      // blocks.
      final var start = (int) Math.min(Math.max(from, 0), events.size());
      for (final var event : events.subList(start, events.size())) {
        reader.onEvent(event);
      }
      readers.add(reader);
    }
    if (!live()) {
      detach(reader);
      return false;
    }
    return true;
  }

  public void detach(final Reader reader) {
    readers.remove(reader);
  }

  /** Closes every reader, for when the journal is being evicted. */
  void closeReaders() {
    for (final var reader : List.copyOf(readers)) {
      readers.remove(reader);
      try {
        reader.onClosed();
      } catch (final Exception e) {
        log.debug("Reader of run {} would not close: {}", requestId, e.toString());
      }
    }
  }

  public int size() {
    synchronized (lock) {
      return events.size();
    }
  }
}
