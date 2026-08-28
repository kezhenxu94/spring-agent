package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.Locale;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
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
 * The pane holding what the run has done. Its title says which tool the run is on, which is what a
 * reader watching a working card wants, and every call it has made — the one still out included —
 * is a pane inside it, which is what a reader checking a finished answer wants. A subagent has no
 * pane and says its calls in a line under its own report, which is the other half of the tests
 * here.
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
              null, null, null, null, null, null, null, Locale.ENGLISH, null, null));

  private FeishuCardUpdater updater;

  @BeforeEach
  void setUp() throws Exception {
    final var streamed = new ContentCardElementResp();
    streamed.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(streamed);
    // The pane is inserted on the first call of the turn and replaced whole on every one after it.
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    final var updated = new UpdateCardElementResp();
    updated.setCode(0);
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenReturn(updated);
    updater =
        FeishuCardUpdater.forRun(
            new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages),
            om,
            null,
            messages,
            cardElements(messages),
            null);
  }

  @Test
  @DisplayName("the first call of a turn puts the pane on the card, already holding that call")
  void theFirstCallBringsThePane() throws Exception {
    updater.setToolStatus(
        "Bash", "{\"command\":\"ls -la\",\"description\":\"Listing files\"}", null);

    final var pane = insertedPane();
    assertThat(pane.path("element_id").asString()).isEqualTo("tools");
    // Which tool the run is on, so that a folded pane still says what is happening.
    assertThat(title(pane)).isEqualTo("**Tool calls** — Bash ...");
    // The call itself is inside, like every other: named by its tool and by what the model said
    // the call was for, opening onto what it was given. The description names the line rather than
    // sitting among the fields, so a pane full of Bash calls says which is which unopened.
    assertThat(title(call(pane, 0))).isEqualTo("Bash — Listing files");
    assertThat(bodyOf(call(pane, 0))).isEqualTo("> command: ls -la");
    // Open, because while a run is calling tools that is the only thing there is to watch.
    assertThat(pane.path("expanded").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("every call is a pane inside, oldest first, the one still out among them")
  void everyCallIsKept() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    updater.setToolStatus("ReadFile", "{\"description\":\"Reading the log\"}", null);
    updater.setToolStatus("Kubectl", "{\"description\":\"Restarting it\"}", null);

    final var pane = lastPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** — Kubectl ...");
    final var elements = pane.path("elements");
    // Oldest at the top, newest at the bottom, each named by its tool so the trail reads as a list
    // of what the run used. The call the title names is the last of them, not held out above.
    assertThat(title(elements.path(0))).isEqualTo("Bash — Listing files");
    assertThat(title(elements.path(1))).isEqualTo("ReadFile — Reading the log");
    assertThat(title(elements.path(2))).isEqualTo("Kubectl — Restarting it");
    assertThat(elements).hasSize(3);
    // Each one closed: a turn can make dozens of calls and a reader wants one of them.
    assertThat(elements.path(0).path("expanded").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("once every call is back the pane is named by how many it holds")
  void theFinishedPaneIsNamedByItsSize() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.setToolStatus("ReadFile", "{\"path\":\"a.txt\"}", null);
    updater.clearToolStatus("Bash", "{}", "a.txt");
    updater.clearToolStatus("ReadFile", "{}", "hello");

    final var pane = lastPane();
    // Nothing is out, so no tool is the one the run is on. Going on naming the last call would say
    // the run is still waiting on a call that is over.
    assertThat(title(pane)).isEqualTo("**Tool calls** (2)");
    // And the last call is in the list with the rest, which is the only place its result shows.
    assertThat(bodyOf(call(pane, 1))).isEqualTo("> path: a.txt\n\n> output: hello");
  }

  @Test
  @DisplayName("what a call returned is in its own pane, under what it was called with")
  void aCallKeepsWhatItReturned() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"Listing files\"}", null);
    updater.clearToolStatus("Bash", "{\"command\":\"ls\"}", "a.txt\nb.txt");
    updater.setToolStatus("Bash", "{\"description\":\"Reading the log\"}", null);

    // Quoted like the input above it, labelled so the two halves are told apart, and every line
    // of it prefixed so a listing stays one quote rather than breaking into several.
    assertThat(bodyOf(call(lastPane(), 0))).isEqualTo("> command: ls\n\n> output: a.txt\n> b.txt");
  }

  @Test
  @DisplayName("a result that arrives JSON-encoded is shown decoded, with its lines back")
  void anEncodedResultIsDecoded() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"dig ns\"}", null);
    updater.clearToolStatus("Bash", "{}", "\"=== NS ===\\njade.ns.example.com.\"");

    // A tool result reaches the card JSON-encoded, so the newlines in it arrive written as two
    // characters. Shown as they came, a whole log was one unreadable line.
    assertThat(bodyOf(call(lastPane(), 0)))
        .isEqualTo("> command: dig ns\n\n> output: === NS ===\n> jade.ns.example.com.");
  }

  @Test
  @DisplayName("a blank line inside a result stays a line rather than vanishing into its neighbors")
  void aBlankLineInAResultIsKept() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"whoami\"}", null);
    updater.clearToolStatus("Bash", "{}", "bash_id: shell_1\n\nroot");

    // Left as "> " with nothing after it, Feishu's card renderer collapses the line, joining
    // "shell_1" straight into "root" with no separator. A non-breaking space after the marker
    // keeps the blank line a line, so the two stay apart.
    assertThat(bodyOf(call(lastPane(), 0)))
        .isEqualTo("> command: whoami\n\n> output: bash_id: shell_1\n>  \n> root");
  }

  @Test
  @DisplayName("a result that is a JSON object is laid out field by field, as an input is")
  void anObjectResultIsLaidOut() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    updater.clearToolStatus("Bash", "{}", "{\"exitCode\":0,\"stdout\":\"a.txt\"}");

    assertThat(bodyOf(call(lastPane(), 0)))
        .isEqualTo("> command: ls\n\n> output: exitCode: 0\n> stdout: a.txt");
  }

  @Test
  @DisplayName("a call still out shows what it was given and nothing more")
  void aCallStillOutHasNoResult() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 60\"}", null);
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);

    assertThat(bodyOf(call(lastPane(), 0))).isEqualTo("> command: sleep 60");
    assertThat(bodyOf(call(lastPane(), 1))).isEqualTo("> command: ls");
  }

  @Test
  @DisplayName("the pane is folded away once the run has an answer to read instead")
  void thePaneIsFoldedWhenTheRunEnds() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);

    updater.onFinished(AgentOutcome.COMPLETED);

    final var pane = lastPane();
    assertThat(pane.path("expanded").asBoolean()).isFalse();
    // Folded, not emptied: the trail is the point of keeping it. And still named by the call that
    // never came back, since that is what was true when the run stopped.
    assertThat(title(pane)).isEqualTo("**Tool calls** — Bash ...");
    assertThat(title(call(pane, 0))).isEqualTo("Bash — Listing files");
  }

  @Test
  @DisplayName("a turn making more calls than the pane holds says how many it is not showing")
  void theOldestCallsAreSaidInALine() throws Exception {
    for (var i = 1; i <= 24; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }

    final var elements = lastPane().path("elements");
    assertThat(title(lastPane())).isEqualTo("**Tool calls** — Tool24 ...");
    // The line stands where the calls it stands for would have been: above the oldest one shown.
    assertThat(elements.path(0).path("content").asString()).isEqualTo("… and 4 earlier calls");
    assertThat(title(elements.path(1))).isEqualTo("Tool5");
    assertThat(title(elements.path(elements.size() - 1))).isEqualTo("Tool24");
  }

  @Test
  @DisplayName("the model's description of a call names its pane, rather than sitting in it")
  void theDescriptionNamesTheCallsPane() throws Exception {
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

  @Test
  @DisplayName("a call the model described in no words is the same pane as one it did")
  void aCallWithoutADescription() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\",\"timeout\":1000}", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** \u2014 Bash ...");
    // The tool alone names it, with nothing after the name to say what the call was for.
    assertThat(title(call(pane, 0))).isEqualTo("Bash");
    assertThat(bodyOf(call(pane, 0))).isEqualTo("> command: ls -la\n> timeout: 1000");
  }

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

  @Test
  @DisplayName("input that is not a JSON object is quoted as it came")
  void nonObjectInputIsQuotedVerbatim() throws Exception {
    updater.setToolStatus("Bash", "not json at all", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** \u2014 Bash ...");
    assertThat(bodyOf(call(pane, 0))).isEqualTo("> not json at all");
  }

  @Test
  @DisplayName("a field holding an array is shown as JSON, not dropped along with the rest")
  void containerFieldsSurvive() throws Exception {
    updater.setToolStatus("TodoWrite", "{\"todos\":[{\"content\":\"do it\"}]}", null);

    assertThat(bodyOf(call(insertedPane(), 0))).isEqualTo("> todos: [{\"content\":\"do it\"}]");
  }

  @Test
  @DisplayName("an empty input adds nothing under the line")
  void emptyInputAddsNothing() throws Exception {
    updater.setToolStatus("DateTime", "", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("**Tool calls** \u2014 DateTime ...");
    assertThat(bodyOf(call(pane, 0))).isEmpty();
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
        card, om, null, messages, panels, "sub_1", "Reading the timeline", "Read it");
  }

  /** What was last streamed into one element of the card. */
  private String lastContentOf(final String elementId) throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    final var writes =
        captor.getAllValues().stream()
            .filter(request -> elementId.equals(request.getElementId()))
            .toList();
    assertThat(writes).as("nothing was written to " + elementId).isNotEmpty();
    return writes.get(writes.size() - 1).getContentCardElementReqBody().getContent();
  }

  /** The pane as it went onto the card, which is the first call of the turn. */
  private JsonNode insertedPane() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    return om.readTree(captor.getValue().getCreateCardElementReqBody().getElements()).path(0);
  }

  /** The pane as the card last had it: replaced whole on every call after the first. */
  private JsonNode lastPane() throws Exception {
    final var captor = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeast(0)).update(captor.capture());
    final var panes =
        captor.getAllValues().stream()
            .filter(request -> "tools".equals(request.getElementId()))
            .toList();
    return panes.isEmpty()
        ? insertedPane()
        : om.readTree(panes.get(panes.size() - 1).getUpdateCardElementReqBody().getElement());
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
