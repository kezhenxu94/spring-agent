package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pane holding what the run has done: every call the turn made, a pane each, inside one pane
 * under the answer.
 *
 * <p>Most of what is asserted here is about <i>which writes the run makes</i> rather than about
 * what the card ends up showing, and that is the point. Feishu reports a panel's chevron to nobody,
 * so an element sent again is an element drawn again closed — a pane rebuilt on every call would
 * snap shut every call a reader had opened, several times a minute. So a call is appended as it
 * goes out and that one pane rewritten as it comes back, and the tests below check that nothing
 * else is touched.
 *
 * <p>A subagent has no pane and says its calls in a line under its own report, which is the other
 * half of the tests here.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterToolStatusTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  private final FeishuMessages messages =
      new FeishuMessages(
          new FeishuProperties(
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));

  private FeishuCardUpdater updater;

  /**
   * Every write the run made, in the order it made them, which is what the two kinds of assertion
   * here need: a captor per API method cannot say whether the pane was rewritten before or after a
   * call was appended to it.
   */
  private final List<Write> writes = new ArrayList<>();

  /**
   * The clock the run reads a call's duration from, in nanoseconds and under the test's control.
   * There is no other way to say what a card shows for a call that took two seconds without the
   * test taking two seconds.
   */
  private final AtomicLong clock = new AtomicLong();

  /**
   * One write to the card: what it did, what it named, and the JSON it carried.
   *
   * @param kind {@code insert} above an element of the card, {@code append} into a container,
   *     {@code update} of one element, {@code delete} of one
   */
  private record Write(String kind, String target, JsonNode payload) {}

  @BeforeEach
  void setUp() throws Exception {
    final var streamed = new ContentCardElementResp();
    streamed.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(streamed);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            call -> {
              final var body =
                  call.<CreateCardElementReq>getArgument(0).getCreateCardElementReqBody();
              writes.add(
                  new Write(
                      "append".equals(body.getType()) ? "append" : "insert",
                      body.getTargetElementId(),
                      om.readTree(body.getElements()).path(0)));
              final var response = new CreateCardElementResp();
              response.setCode(0);
              return response;
            });
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            call -> {
              final var request = call.<UpdateCardElementReq>getArgument(0);
              writes.add(
                  new Write(
                      "update",
                      request.getElementId(),
                      om.readTree(request.getUpdateCardElementReqBody().getElement())));
              final var response = new UpdateCardElementResp();
              response.setCode(0);
              return response;
            });
    updater =
        FeishuCardUpdater.forRun(
            new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages),
            om,
            null,
            messages,
            cardElements(messages),
            null);
    updater.nanoTime = clock::get;
  }

  /** Answers the deletes too, for the tests whose window slides past a call. */
  private void answerDeletes() throws Exception {
    when(feishu.cardkit().v1().cardElement().delete(any(DeleteCardElementReq.class)))
        .thenAnswer(
            call -> {
              writes.add(
                  new Write(
                      "delete", call.<DeleteCardElementReq>getArgument(0).getElementId(), null));
              final var response = new DeleteCardElementResp();
              response.setCode(0);
              return response;
            });
  }

  // -----------------------------------------------------------------------------------------
  // What the pane holds
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("the first call of a turn puts the pane on the card, already holding that call")
  void theFirstCallBringsThePane() {
    updater.setToolStatus(
        "Bash", "{\"command\":\"ls -la\",\"description\":\"Listing files\"}", null);

    final var pane = insertedPane();
    assertThat(pane.path("element_id").asString()).isEqualTo("tools");
    // The title says a run is calling tools and no more than that: naming the tool of the moment
    // would mean rewriting the panel on every call, which closes every pane inside it.
    assertThat(title(pane)).isEqualTo("**Tool calls** ...");
    // The call itself is inside, like every other: named by its tool and by what the model said
    // the call was for, opening onto what it was given. The description names the line rather than
    // sitting among the fields, so a pane full of Bash calls says which is which unopened.
    assertThat(title(call(pane, 0))).isEqualTo("Bash — Listing files");
    assertThat(bodyOf(call(pane, 0))).isEqualTo("> command: ls -la");
    // And carries the id the run addresses it by from here on.
    assertThat(call(pane, 0).path("element_id").asString()).isEqualTo("tool_call_0");
    // Open, because while a run is calling tools that is the only thing there is to watch.
    assertThat(pane.path("expanded").asBoolean()).isTrue();
    // Each call closed: a turn can make dozens of them and a reader wants one.
    assertThat(call(pane, 0).path("expanded").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("every call is a pane inside, oldest first, the one still out among them")
  void everyCallIsKept() {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    updater.setToolStatus("ReadFile", "{\"description\":\"Reading the log\"}", null);
    updater.setToolStatus("Kubectl", "{\"description\":\"Restarting it\"}", null);

    // The first one arrives inside the pane; the two after it are appended into it, in order.
    assertThat(title(call(insertedPane(), 0))).isEqualTo("Bash — Listing files");
    assertThat(title(appended(0))).isEqualTo("ReadFile — Reading the log");
    assertThat(title(appended(1))).isEqualTo("Kubectl — Restarting it");
    assertThat(appended(0).path("element_id").asString()).isEqualTo("tool_call_1");
    assertThat(appended(1).path("element_id").asString()).isEqualTo("tool_call_2");
  }

  @Test
  @DisplayName("what a call returned is in its own pane, under what it was called with")
  void aCallKeepsWhatItReturned() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"Listing files\"}", null);
    updater.clearToolStatus("Bash", "{\"command\":\"ls\"}", "a.txt\nb.txt");

    // Quoted like the input above it, labelled so the two halves are told apart, and every line
    // of it prefixed so a listing stays one quote rather than breaking into several.
    assertThat(bodyOf(updated("tool_call_0")))
        .isEqualTo("> command: ls\n\n> output: a.txt\n> b.txt");
  }

  @Test
  @DisplayName("a result that arrives JSON-encoded is shown decoded, with its lines back")
  void anEncodedResultIsDecoded() {
    updater.setToolStatus("Bash", "{\"command\":\"dig ns\"}", null);
    updater.clearToolStatus("Bash", "{}", "\"=== NS ===\\njade.ns.example.com.\"");

    // A tool result reaches the card JSON-encoded, so the newlines in it arrive written as two
    // characters. Shown as they came, a whole log was one unreadable line.
    assertThat(bodyOf(updated("tool_call_0")))
        .isEqualTo("> command: dig ns\n\n> output: === NS ===\n> jade.ns.example.com.");
  }

  @Test
  @DisplayName("a blank line inside a result stays a line rather than vanishing into its neighbors")
  void aBlankLineInAResultIsKept() {
    updater.setToolStatus("Bash", "{\"command\":\"whoami\"}", null);
    updater.clearToolStatus("Bash", "{}", "bash_id: shell_1\n\nroot");

    // Left as "> " with nothing after it, Feishu's card renderer collapses the line, joining
    // "shell_1" straight into "root" with no separator. A non-breaking space after the marker
    // keeps the blank line a line, so the two stay apart.
    assertThat(bodyOf(updated("tool_call_0")))
        .isEqualTo("> command: whoami\n\n> output: bash_id: shell_1\n> \u00a0\n> root");
  }

  @Test
  @DisplayName("a result that is a JSON object is laid out field by field, as an input is")
  void anObjectResultIsLaidOut() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.clearToolStatus("Bash", "{}", "{\"exitCode\":0,\"stdout\":\"a.txt\"}");

    assertThat(bodyOf(updated("tool_call_0")))
        .isEqualTo("> command: ls\n\n> output: exitCode: 0\n> stdout: a.txt");
  }

  @Test
  @DisplayName("a call still out shows what it was given and nothing more")
  void aCallStillOutHasNoResult() {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 60\"}", null);
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);

    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> command: sleep 60");
    assertThat(bodyOf(appended(0))).isEqualTo("> command: ls");
    // Nothing was rewritten: neither call is back, so neither pane has anything new to say.
    assertThat(kinds("update")).isEmpty();
  }

  @Test
  @DisplayName("the oldest call of a tool is the one a result belongs to")
  void aResultGoesToTheOldestCallOfThatToolStillOut() {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 60\"}", null);
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);

    // A round can have several calls of one tool out at once, and they come back in whatever order
    // they finish in; the wire says which tool answered and not which call.
    updater.clearToolStatus("Bash", "{}", "a.txt");

    assertThat(bodyOf(updated("tool_call_0"))).isEqualTo("> command: sleep 60\n\n> output: a.txt");
    assertThat(writes.stream().filter(w -> "tool_call_1".equals(w.target())).toList()).isEmpty();
  }

  // -----------------------------------------------------------------------------------------
  // How long a call took
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("a call that has come back says how long it took, beside the tool that took it")
  void aCallSaysHowLongItTook() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"Listing files\"}", null);
    clock.set(1_400_000_000L);
    updater.clearToolStatus("Bash", "{}", "a.txt");

    // Beside the tool rather than at the end: what a reader is scanning for is the call the turn
    // went on, and after a sentence of the model's own the timings would be strewn down the pane
    // at whatever column each description happened to end in.
    assertThat(title(updated("tool_call_0"))).isEqualTo("Bash (1.4s) — Listing files");
  }

  @Test
  @DisplayName("a call the model described in no words still says how long it took")
  void anUndescribedCallStillSaysItsDuration() {
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\"}", null);
    clock.set(12_050_000_000L);
    updater.clearToolStatus("Bash", "{}", "a.txt");

    assertThat(title(updated("tool_call_0"))).isEqualTo("Bash (12.1s)");
  }

  @Test
  @DisplayName("a call still out says no duration, which is what says it is still out")
  void aCallStillOutHasNoDuration() {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 60\"}", null);
    clock.set(30_000_000_000L);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);

    assertThat(title(call(insertedPane(), 0))).isEqualTo("Bash");
    assertThat(title(appended(0))).isEqualTo("ReadFile");
  }

  @Test
  @DisplayName("each call is timed from when it went out, not from when the turn started")
  void eachCallIsTimedFromItsOwnStart() {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 5\"}", null);
    clock.set(5_000_000_000L);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);
    clock.set(5_200_000_000L);
    updater.clearToolStatus("ReadFile", "{}", "hello");
    clock.set(9_000_000_000L);
    updater.clearToolStatus("Bash", "{}", "done");

    // The slow call is the slow call however late in the turn the quick one ran, which is the
    // whole reason the durations are on the card.
    assertThat(title(updated("tool_call_1"))).isEqualTo("ReadFile (0.2s)");
    assertThat(title(updated("tool_call_0"))).isEqualTo("Bash (9.0s)");
  }

  @Test
  @DisplayName("the durations survive the pane being built again")
  void theDurationsSurviveARebuild() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    clock.set(2_000_000_000L);
    updater.clearToolStatus("Bash", "{}", "a.txt");

    updater.onFinished(AgentOutcome.COMPLETED);

    // The run keeps what each call took rather than the card doing so, which is what lets the pane
    // be rebuilt at the end with every timing still on it.
    assertThat(title(call(updated("tools"), 0))).isEqualTo("Bash (2.0s)");
  }

  // -----------------------------------------------------------------------------------------
  // What a reader has opened stays open
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("a call is appended to the pane rather than the pane being written again")
  void aCallIsAppendedRatherThanThePaneRewritten() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);
    updater.setToolStatus("Kubectl", "{\"n\":1}", null);

    // The pane goes on the card once, and every call after the first is appended into it. Sending
    // the panel again is what would close the panes a reader had opened.
    assertThat(kinds("insert")).containsExactly("usage");
    assertThat(kinds("append")).containsExactly("tools", "tools");
    assertThat(kinds("update")).isEmpty();
  }

  @Test
  @DisplayName("a call coming back rewrites that call's pane and nothing else")
  void aResultRewritesOneCallOnly() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);
    updater.clearToolStatus("Bash", "{}", "a.txt");

    // The one pane whose title and body have changed. The panel around it, and the other call's
    // pane, are left exactly as the reader has them.
    assertThat(kinds("update")).containsExactly("tool_call_0");
  }

  // -----------------------------------------------------------------------------------------
  // The end of the run
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("the pane is folded away once the run has an answer to read instead")
  void thePaneIsFoldedWhenTheRunEnds() {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);

    updater.onFinished(AgentOutcome.COMPLETED);

    final var pane = updated("tools");
    assertThat(pane.path("expanded").asBoolean()).isFalse();
    // Folded, not emptied: the trail is the point of keeping it.
    assertThat(title(call(pane, 0))).isEqualTo("Bash — Listing files");
  }

  @Test
  @DisplayName("the title says how many calls the turn made, once the run is over")
  void theFinishedPaneIsNamedByItsSize() {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);
    updater.clearToolStatus("Bash", "{}", "a.txt");
    updater.clearToolStatus("ReadFile", "{}", "hello");

    updater.onFinished(AgentOutcome.COMPLETED);

    // The one time the panel itself is rewritten, which is also the one time it can say a count:
    // it is being folded away anyway, so there is no open pane left inside it to lose.
    assertThat(title(updated("tools"))).isEqualTo("**Tool calls**(2)");
  }

  @Test
  @DisplayName("the pane is written again exactly once as the run ends")
  void thePaneIsRewrittenOnceAtTheEnd() {
    for (var i = 1; i <= 5; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
      updater.clearToolStatus("Tool" + i, "{}", "ok");
    }

    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(kinds("update").stream().filter("tools"::equals).toList()).hasSize(1);
  }

  // -----------------------------------------------------------------------------------------
  // A turn longer than the pane holds
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("a turn making more calls than the pane holds says how many it is not showing")
  void theOldestCallsAreSaidInALine() throws Exception {
    answerDeletes();
    for (var i = 1; i <= 24; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }

    final var elements = updated("tools").path("elements");
    // The line stands where the calls it stands for would have been: above the oldest one shown.
    assertThat(elements.path(0).path("content").asString()).isEqualTo("… and 1 earlier calls");
    assertThat(elements.path(0).path("element_id").asString()).isEqualTo("tools_earlier");
    assertThat(title(elements.path(1))).isEqualTo("Tool2");
  }

  @Test
  @DisplayName("the first call to fall out of the window is what puts the line on the card")
  void theWindowSlidesByRebuildingOnceAndThenIncrementally() throws Exception {
    answerDeletes();
    for (var i = 1; i <= 23; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }

    // A line that is not on the card cannot be corrected in place, so the call that first pushes
    // one out of the window is answered by building the pane again — once — and every call after
    // it drops the oldest pane and rewrites the count.
    assertThat(kinds("update").stream().filter("tools"::equals).toList()).hasSize(1);
    assertThat(kinds("delete")).containsExactly("tool_call_1", "tool_call_2");
    assertThat(kinds("update").stream().filter("tools_earlier"::equals).toList()).hasSize(2);
    assertThat(updated("tools_earlier").path("content").asString())
        .isEqualTo("… and 3 earlier calls");
    // And the calls still shown are appended as ever: the window sliding is not a rebuild.
    assertThat(kinds("append").stream().filter("tools"::equals).toList()).hasSize(21);
  }

  @Test
  @DisplayName("a result for a call the window has dropped changes nothing on the card")
  void aResultForADroppedCallIsNotWritten() throws Exception {
    answerDeletes();
    updater.setToolStatus("Tool0", "{\"n\":0}", null);
    for (var i = 1; i <= 22; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }
    writes.clear();

    updater.clearToolStatus("Tool0", "{}", "ok");

    // Its pane is behind the line saying how many the pane has dropped. There is nothing on the
    // card to rewrite, and putting the pane back to say so would close every call a reader opened.
    assertThat(writes).isEmpty();
  }

  @Test
  @DisplayName("the count the run ends with is every call the turn made, dropped panes included")
  void theFinalCountIncludesTheDroppedCalls() throws Exception {
    answerDeletes();
    for (var i = 1; i <= 24; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }

    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(title(updated("tools"))).isEqualTo("**Tool calls**(24)");
  }

  // -----------------------------------------------------------------------------------------
  // How a call is named
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("the model's description of a call names its pane, rather than sitting in it")
  void theDescriptionNamesTheCallsPane() {
    updater.setToolStatus(
        "Bash",
        "{\"command\":\"ls -la\",\"description\":\"List files in the current directory\"}",
        null);

    // The one thing that tells one Bash call from the next without opening either, so it goes on
    // the line a closed pane shows — and is then left out of the fields, not said twice.
    assertThat(title(call(insertedPane(), 0)))
        .isEqualTo("Bash — List files in the current directory");
    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> command: ls -la");
  }

  /**
   * Which is what makes the pane readable for a tool nobody here wrote: an MCP server's tool asks
   * for no description of its own, so the runtime asks for one on the card's behalf.
   */
  @Test
  @DisplayName("a tool with no description of its own names its pane by the one the runtime asked")
  void theRuntimesDescriptionNamesTheCallsPane() {
    updater.setToolStatus(
        "jira_search",
        "{\"jql\":\"project = OPS\",\""
            + DisplayDescription.FIELD
            + "\":\"Look for open OPS tickets\"}",
        null);

    assertThat(title(call(insertedPane(), 0))).isEqualTo("jira_search — Look for open OPS tickets");
    // Not among the fields either. It is the line above them, and it is not a parameter of the
    // tool for a reader to be shown.
    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> jql: project = OPS");
  }

  @Test
  @DisplayName("a tool that asks for a description of its own is the one the pane is named by")
  void theToolsOwnDescriptionWins() {
    updater.setToolStatus(
        "Bash",
        "{\"command\":\"ls\",\"description\":\"Listing files\",\""
            + DisplayDescription.FIELD
            + "\":\"Also listing files\"}",
        null);

    assertThat(title(call(insertedPane(), 0))).isEqualTo("Bash — Listing files");
    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> command: ls");
  }

  @Test
  @DisplayName("a call the model described in no words is the same pane as one it did")
  void aCallWithoutADescription() {
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\",\"timeout\":1000}", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** ...");
    // The tool alone names it, with nothing after the name to say what the call was for.
    assertThat(title(call(pane, 0))).isEqualTo("Bash");
    assertThat(bodyOf(call(pane, 0))).isEqualTo("> command: ls -la\n> timeout: 1000");
  }

  @Test
  @DisplayName("input that is not a JSON object is quoted as it came")
  void nonObjectInputIsQuotedVerbatim() {
    updater.setToolStatus("Bash", "not json at all", null);

    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> not json at all");
  }

  @Test
  @DisplayName("a field holding an array is shown as JSON, not dropped along with the rest")
  void containerFieldsSurvive() {
    updater.setToolStatus("TodoWrite", "{\"todos\":[{\"content\":\"do it\"}]}", null);

    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> todos: [{\"content\":\"do it\"}]");
  }

  @Test
  @DisplayName("an empty input adds nothing under the line")
  void emptyInputAddsNothing() {
    updater.setToolStatus("DateTime", "", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** ...");
    assertThat(bodyOf(call(pane, 0))).isEmpty();
  }

  // -----------------------------------------------------------------------------------------
  // A subagent, which has no pane
  // -----------------------------------------------------------------------------------------

  @Test
  @DisplayName("a subagent says its calls in a line, described the way the model described them")
  void aSubagentDescribesItsCallsInline() throws Exception {
    final var subagent = subagentUpdater();

    subagent.onContent("Reading it now.");
    subagent.setToolStatus(
        "Bash",
        "{\"command\":\"ls\",\"description\":\"  List files\\n   in the directory  \"}",
        null);

    // A panel has no pane of its own, so this line is the whole of what a reader sees of a call:
    // the model's description rather than the tool's name, folded onto the one line the panel
    // gives it, and the fields under it without it said twice.
    assertThat(lastContentOf(FeishuSubagentPanel.bodyElementId("sub_1")))
        .isEqualTo("Reading it now.\nList files in the directory\n> command: ls");
  }

  @Test
  @DisplayName("a subagent's line falls back to the tool where the description is unusable")
  void aSubagentFallsBackToTheToolName() throws Exception {
    final var subagent = subagentUpdater();

    subagent.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"   \"}", null);

    // The tool names the call, and the description it could not use stays among the fields rather
    // than being dropped on a judgement about what a reader would rather not see.
    assertThat(lastContentOf(FeishuSubagentPanel.bodyElementId("sub_1")))
        .isEqualTo("\nCalling Bash ...\n> command: ls\n> description:    ");
  }

  @Test
  @DisplayName("the line comes off a subagent's panel once every call of the round is back")
  void aSubagentsLineComesOffWhenTheCallsReturn() throws Exception {
    final var subagent = subagentUpdater();

    subagent.onContent("Let me look.");
    subagent.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    subagent.setToolStatus("Bash", "{\"description\":\"Reading the log\"}", null);
    subagent.clearToolStatus("Bash", "{}", "a.txt");

    // The first call back does not speak for the second: the run is still waiting on something.
    assertThat(lastContentOf(FeishuSubagentPanel.bodyElementId("sub_1")))
        .isEqualTo("Let me look.\nReading the log");

    subagent.clearToolStatus("Bash", "{}", "the log");

    // And nothing else would take the line down until the model wrote its next word, which on a
    // model that thinks before it writes is a long wait.
    assertThat(lastContentOf(FeishuSubagentPanel.bodyElementId("sub_1"))).isEqualTo("Let me look.");
  }

  /** A subagent of that run, panel and all, as {@code FeishuCardListener} attaches one. */
  private FeishuCardUpdater subagentUpdater() {
    final var panels = new FeishuSubagentPanel(om, messages);
    panels.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    final var card =
        new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    card.insertBeforeFooter(
        panels.forInsert("sub_1", "Reading the timeline", "Read it and say when it starts", null),
        "sub_1");
    return FeishuCardUpdater.forSubagent(
        card, om, null, messages, panels, null, "sub_1", "Reading the timeline", "Read it");
  }

  // -----------------------------------------------------------------------------------------
  // Reading the writes back
  // -----------------------------------------------------------------------------------------

  /** What was last streamed into one element of the card. */
  private String lastContentOf(final String elementId) throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    final var streams =
        captor.getAllValues().stream()
            .filter(request -> elementId.equals(request.getElementId()))
            .toList();
    assertThat(streams).as("nothing was written to " + elementId).isNotEmpty();
    return streams.get(streams.size() - 1).getContentCardElementReqBody().getContent();
  }

  /** What each write of one kind named, oldest first. */
  private List<String> kinds(final String kind) {
    return writes.stream().filter(write -> kind.equals(write.kind())).map(Write::target).toList();
  }

  /** The pane as it went onto the card, which is the first call of the turn. */
  private JsonNode insertedPane() {
    return payloadOf("insert", "usage");
  }

  /** The {@code index}-th call appended into the pane, counting from the turn's second call. */
  private JsonNode appended(final int index) {
    final var appends =
        writes.stream()
            .filter(write -> "append".equals(write.kind()) && "tools".equals(write.target()))
            .toList();
    assertThat(appends)
        .as("fewer than " + (index + 1) + " calls were appended")
        .hasSizeGreaterThan(index);
    return appends.get(index).payload();
  }

  /** One element as it was last written again, whole. */
  private JsonNode updated(final String elementId) {
    return payloadOf("update", elementId);
  }

  private JsonNode payloadOf(final String kind, final String target) {
    final var matching =
        writes.stream()
            .filter(write -> kind.equals(write.kind()) && target.equals(write.target()))
            .toList();
    assertThat(matching).as("no " + kind + " named " + target).isNotEmpty();
    return matching.get(matching.size() - 1).payload();
  }

  /** The i-th call's pane inside the tool pane, counting from the oldest one it shows. */
  private static JsonNode call(final JsonNode pane, final int index) {
    return pane.path("elements").path(index);
  }

  /** What opening one call's pane shows. */
  private static String bodyOf(final JsonNode callPane) {
    return callPane.path("elements").path(0).path("content").asString();
  }

  /** A panel's title without the styling the template wrapped it in. */
  private static String title(final JsonNode panel) {
    return panel
        .path("header")
        .path("title")
        .path("content")
        .asString()
        .replace("<font color='grey'>", "")
        .replace("</font>", "");
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
