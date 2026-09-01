package me.kezhenxu94.springagent.integration.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.integration.websocket.run.RunEvent;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournal;
import me.kezhenxu94.springagent.integration.websocket.run.WebRunRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal is what makes a browser optional, so what is pinned here is the part a reconnect
 * depends on: that a reader which says how far it got is given the rest exactly once, in order.
 */
class RunJournalTest {

  /** Records what it was told, which is all a reconnecting browser is. */
  private static final class Recorder implements RunJournal.Reader {
    final List<RunEvent> seen = new ArrayList<>();
    boolean closed;

    @Override
    public void onEvent(final RunEvent event) {
      seen.add(event);
    }

    @Override
    public void onClosed() {
      closed = true;
    }
  }

  private static RunJournal journal() {
    return new RunJournal("run-1", "conversation-1", "ou_user");
  }

  private static RunEvent content(final String text) {
    return RunEvent.of(RunEvent.CONTENT, Map.of("delta", text));
  }

  @Test
  @DisplayName("events are numbered from one, in the order they were appended")
  void eventsAreNumbered() {
    final var journal = journal();
    journal.append(content("a"));
    journal.append(content("b"));

    final var reader = new Recorder();
    journal.attach(reader, 0);

    assertThat(reader.seen).extracting(RunEvent::seq).containsExactly(1L, 2L);
    assertThat(reader.seen).extracting(it -> it.data().get("delta")).containsExactly("a", "b");
  }

  @Test
  @DisplayName("a reader that says how far it got is replayed from there and no earlier")
  void replayResumesFromTheCursor() {
    final var journal = journal();
    journal.append(content("a"));
    journal.append(content("b"));
    journal.append(content("c"));

    final var reader = new Recorder();
    journal.attach(reader, 2);

    // The whole of the reconnect contract: what the browser already has is not sent again, and
    // nothing after it is skipped.
    assertThat(reader.seen).extracting(RunEvent::seq).containsExactly(3L);
  }

  @Test
  @DisplayName("the handover from replay to live neither drops nor repeats an event")
  void replayHandsOverToLiveCleanly() {
    final var journal = journal();
    journal.append(content("a"));

    final var reader = new Recorder();
    journal.attach(reader, 0);
    journal.append(content("b"));

    assertThat(reader.seen).extracting(RunEvent::seq).containsExactly(1L, 2L);
  }

  @Test
  @DisplayName("a reader attaching after the run has ended is replayed and told there is no more")
  void attachingToAFinishedRunReplaysAndCloses() {
    final var journal = journal();
    journal.append(content("a"));
    // Through the renderer rather than by reaching into the journal: marking a journal finished is
    // the renderer's job, and doing it here would let the test pass while the real path did not.
    new WebRunRenderer(journal, null).onFinished(AgentOutcome.COMPLETED);

    final var reader = new Recorder();
    final var stillLive = journal.attach(reader, 0);

    assertThat(stillLive).isFalse();
    assertThat(reader.seen).hasSize(2);
    assertThat(journal.live()).isFalse();
    assertThat(journal.finishedAt()).isNotNull();
  }

  @Test
  @DisplayName("a reader that has gone away is dropped, and the run carries on regardless")
  void aDeadReaderDoesNotStopTheRun() {
    final var journal = journal();
    journal.attach(
        new RunJournal.Reader() {
          @Override
          public void onEvent(final RunEvent event) {
            throw new IllegalStateException("the browser has gone");
          }

          @Override
          public void onClosed() {}
        },
        0);

    // The point of the whole design: appending must not fail because nobody is watching.
    journal.append(content("a"));
    journal.append(content("b"));

    assertThat(journal.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("detaching a reader stops it being told anything more")
  void detachStopsDelivery() {
    final var journal = journal();
    final var reader = new Recorder();
    journal.attach(reader, 0);
    journal.append(content("a"));
    journal.detach(reader);
    journal.append(content("b"));

    assertThat(reader.seen).extracting(RunEvent::seq).containsExactly(1L);
  }
}
