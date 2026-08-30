package me.kezhenxu94.springagent.appweb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.appweb.run.RunEvent;
import me.kezhenxu94.springagent.appweb.run.RunJournal;
import me.kezhenxu94.springagent.appweb.run.WebRunRenderer;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;

/** What one run's callbacks turn into on the wire. */
class WebRunRendererTest {

  private static final class Capture implements RunJournal.Reader {
    final java.util.List<RunEvent> seen = new java.util.ArrayList<>();

    @Override
    public void onEvent(final RunEvent event) {
      seen.add(event);
    }

    @Override
    public void onClosed() {}
  }

  private RunJournal journal;
  private Capture capture;

  private WebRunRenderer renderer(final String subagentId) {
    journal = new RunJournal("run-1", "conversation-1", "ou_user");
    capture = new Capture();
    journal.attach(capture, 0);
    return new WebRunRenderer(journal, subagentId);
  }

  private List<RunEvent> of(final String type) {
    return capture.seen.stream().filter(it -> type.equals(it.type())).toList();
  }

  @Test
  @DisplayName("content is sent as the delta, not as the whole answer again")
  void contentIsSentAsDeltas() {
    final var renderer = renderer(null);

    // onContent hands over everything so far on every tick. Re-sending all of it would be quadratic
    // traffic on a long answer, and would make the browser render the answer twice over.
    renderer.onContent("Hello");
    renderer.onContent("Hello, world");
    renderer.onContent("Hello, world");

    assertThat(of(RunEvent.CONTENT))
        .extracting(it -> it.data().get("delta"))
        .containsExactly("Hello", ", world");
  }

  @Test
  @DisplayName("reasoning is streamed the same way, and kept apart from the answer")
  void reasoningIsItsOwnStream() {
    final var renderer = renderer(null);
    renderer.onContent("answer");
    renderer.onReasoning("thinking");

    assertThat(of(RunEvent.REASONING))
        .extracting(it -> it.data().get("delta"))
        .containsExactly("thinking");
    assertThat(of(RunEvent.CONTENT)).hasSize(1);
  }

  @Test
  @DisplayName("a tool result is matched to the call it belongs to")
  void toolResultsCarryTheirCallId() {
    final var renderer = renderer(null);
    renderer.onToolCall("Bash", "{\"command\":\"ls\"}");
    renderer.onToolResult("Bash", "{\"command\":\"ls\"}", "a\nb");

    final var call = of(RunEvent.TOOL).getFirst();
    final var result = of(RunEvent.TOOL_RESULT).getFirst();
    assertThat(call.data().get("name")).isEqualTo("Bash");
    assertThat(result.data().get("id")).isEqualTo(call.data().get("id"));
  }

  @Test
  @DisplayName("every event says which subagent it came from, null for the run itself")
  void eventsAreAttributed() {
    renderer(null).onContent("from the run");
    assertThat(of(RunEvent.CONTENT).getFirst().data()).containsEntry("subagentId", null);

    renderer("sub-1").onContent("from a subagent");
    assertThat(of(RunEvent.CONTENT).getFirst().data()).containsEntry("subagentId", "sub-1");
  }

  @Test
  @DisplayName("a to-do list is sent whole, since it is redrawn rather than appended to")
  void todosAreSentWhole() {
    final var renderer = renderer(null);
    renderer.handle(
        new Todos(
            List.of(
                new Todos.TodoItem("Read the config", Todos.Status.completed, "Reading the config"),
                new Todos.TodoItem("Fix the bug", Todos.Status.in_progress, "Fixing the bug"))));

    @SuppressWarnings("unchecked")
    final var items = (List<Map<String, Object>>) of(RunEvent.TODOS).getFirst().data().get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(1)).containsEntry("activeForm", "Fixing the bug");
  }

  @Test
  @DisplayName("a subagent finishing ends its panel rather than the run")
  void aSubagentFinishingDoesNotFinishTheRun() {
    final var renderer = renderer("sub-1");
    renderer.onFinished(AgentOutcome.COMPLETED);

    assertThat(of(RunEvent.FINISHED)).isEmpty();
    assertThat(of(RunEvent.SUBAGENT).getFirst().data())
        .containsEntry("state", "ended")
        .containsEntry("outcome", "COMPLETED");
    // The journal is the run's, and the run has not ended.
    assertThat(journal.live()).isTrue();
  }

  @Test
  @DisplayName("the run finishing marks the journal, so a late reader is not left waiting")
  void finishingMarksTheJournal() {
    renderer(null).onFinished(AgentOutcome.CANCELLED);

    assertThat(journal.live()).isFalse();
    assertThat(of(RunEvent.FINISHED).getFirst().data()).containsEntry("outcome", "CANCELLED");
  }

  @Test
  @DisplayName("an error reports its message and nothing else")
  void errorsReportTheirMessageOnly() {
    // A stack trace names classes, paths and sometimes a credential in a URL. It belongs in the
    // operator's log, which is where onError also writes it, not in a browser.
    renderer(null).onError(new IllegalStateException("the tool refused"));

    final var error = of(RunEvent.ERROR).getFirst();
    assertThat(error.data()).containsOnlyKeys("message", "subagentId");
    assertThat(error.data().get("message")).isEqualTo("the tool refused");
  }
}
