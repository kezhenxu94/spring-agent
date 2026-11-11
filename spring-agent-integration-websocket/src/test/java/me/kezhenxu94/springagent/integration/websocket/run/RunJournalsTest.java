package me.kezhenxu94.springagent.integration.websocket.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a {@link RunJournalListener} is told, and what one that fails cannot break. */
class RunJournalsTest {

  private final WebProperties properties =
      new WebProperties(null, null, null, null, null, null, null, null, false, null, null);

  @Test
  @DisplayName("a listener hears of each journal as it opens and reads it from the first event")
  void listenerReadsTheRun() {
    final var seen = new ArrayList<RunEvent>();
    final RunJournalListener listener =
        journal ->
            journal.attach(
                new RunJournal.Reader() {
                  @Override
                  public void onEvent(final RunEvent event) {
                    seen.add(event);
                  }

                  @Override
                  public void onClosed() {}
                },
                0);
    final var journal = new RunJournals(properties, List.of(listener)).open("r", "c", "u");
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("delta", "hi")));
    assertThat(seen).extracting(RunEvent::type).containsExactly(RunEvent.CONTENT);
  }

  @Test
  @DisplayName("a listener that throws neither stops the run nor the listeners after it")
  void aFailingListenerIsSkipped() {
    final var opened = new ArrayList<String>();
    final RunJournalListener failing =
        journal -> {
          throw new IllegalStateException("boom");
        };
    final RunJournalListener after = journal -> opened.add(journal.requestId());
    final var journals = new RunJournals(properties, List.of(failing, after));
    assertThat(journals.open("r", "c", "u")).isNotNull();
    assertThat(journals.byRequestId("r")).isPresent();
    assertThat(opened).containsExactly("r");
  }
}
