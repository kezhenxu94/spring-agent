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
 * The pane holding what the run has done. It says on its title which tool the run is on, which is
 * what a reader watching a working card wants, and keeps every call before it behind a chevron,
 * which is what a reader checking a finished answer wants. A subagent has no pane and says its
 * calls in a line under its own report, which is the other half of the tests here.
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
    assertThat(title(pane)).isEqualTo("Tool calls — Bash ...");
    // And the call's input under it, quoted: a call is something the run asked for. The model's
    // description is one of the fields, since no line above says it any more.
    assertThat(pane.path("elements").path(0).path("content").asString())
        .isEqualTo("> command: ls -la\n> description: Listing files");
    // Open, because while a run is calling tools that is the only thing there is to watch.
    assertThat(pane.path("expanded").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("the newest call is the title and the ones before it are panes below, oldest first")
  void everyCallIsKept() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);
    updater.setToolStatus("ReadFile", "{\"description\":\"Reading the log\"}", null);
    updater.setToolStatus("Kubectl", "{\"description\":\"Restarting it\"}", null);

    final var pane = lastPane();
    assertThat(title(pane)).isEqualTo("Tool calls — Kubectl ...");
    final var elements = pane.path("elements");
    // The latest call's input first, then the trail: oldest at the top, newest at the bottom, each
    // named by its tool so the trail reads as a list of what the run used.
    assertThat(title(elements.path(1))).isEqualTo("Bash");
    assertThat(title(elements.path(2))).isEqualTo("ReadFile");
    assertThat(elements).hasSize(3);
    // Each one closed: a turn can make dozens of calls and a reader wants one of them.
    assertThat(elements.path(1).path("expanded").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("what a call returned is in its own pane, under what it was called with")
  void aCallKeepsWhatItReturned() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls\",\"description\":\"Listing files\"}", null);
    updater.clearToolStatus("Bash", "{\"command\":\"ls\"}", "a.txt\nb.txt");
    updater.setToolStatus("Bash", "{\"description\":\"Reading the log\"}", null);

    // Set as code: tool output is a log or a listing, and its shape is most of what makes it read.
    assertThat(
            lastPane().path("elements").path(1).path("elements").path(0).path("content").asString())
        .isEqualTo("> command: ls\n> description: Listing files\n\n```\na.txt\nb.txt\n```");
  }

  @Test
  @DisplayName("a call still out shows what it was given and nothing more")
  void aCallStillOutHasNoResult() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"sleep 60\"}", null);
    updater.setToolStatus("Bash", "{\"command\":\"ls\"}", null);

    assertThat(
            lastPane().path("elements").path(1).path("elements").path(0).path("content").asString())
        .isEqualTo("> command: sleep 60");
  }

  @Test
  @DisplayName("the pane is folded away once the run has an answer to read instead")
  void thePaneIsFoldedWhenTheRunEnds() throws Exception {
    updater.setToolStatus("Bash", "{\"description\":\"Listing files\"}", null);

    updater.onFinished(AgentOutcome.COMPLETED);

    final var pane = lastPane();
    assertThat(pane.path("expanded").asBoolean()).isFalse();
    // Folded, not emptied: the trail is the point of keeping it.
    assertThat(title(pane)).isEqualTo("Tool calls — Bash ...");
  }

  @Test
  @DisplayName("a turn making more calls than the pane holds says how many it is not showing")
  void theOldestCallsAreSaidInALine() throws Exception {
    for (var i = 1; i <= 24; i++) {
      updater.setToolStatus("Tool" + i, "{\"n\":" + i + "}", null);
    }

    final var elements = lastPane().path("elements");
    assertThat(title(lastPane())).isEqualTo("Tool calls — Tool24 ...");
    // The line stands where the calls it stands for would have been: above the oldest one shown.
    assertThat(elements.path(1).path("content").asString()).isEqualTo("… and 3 earlier calls");
    assertThat(title(elements.path(2))).isEqualTo("Tool4");
    assertThat(title(elements.path(elements.size() - 1))).isEqualTo("Tool23");
  }

  @Test
  @DisplayName("the model's description of a call is kept, among the fields it was called with")
  void theDescriptionIsKeptAmongTheFields() throws Exception {
    updater.setToolStatus(
        "Bash",
        "{\"command\":\"ls -la\",\"description\":\"List files in the current directory\"}",
        null);

    // Nothing above it says what the call was for any more, so leaving it out would lose it.
    assertThat(insertedPane().path("elements").path(0).path("content").asString())
        .isEqualTo("> command: ls -la\n> description: List files in the current directory");
  }

  @Test
  @DisplayName("a call the model described in no words is the same pane as one it did")
  void aCallWithoutADescription() throws Exception {
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\",\"timeout\":1000}", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("Tool calls \u2014 Bash ...");
    assertThat(pane.path("elements").path(0).path("content").asString())
        .isEqualTo("> command: ls -la\n> timeout: 1000");
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
    assertThat(title(pane)).isEqualTo("Tool calls \u2014 Bash ...");
    assertThat(pane.path("elements").path(0).path("content").asString())
        .isEqualTo("> not json at all");
  }

  @Test
  @DisplayName("a field holding an array is shown as JSON, not dropped along with the rest")
  void containerFieldsSurvive() throws Exception {
    updater.setToolStatus("TodoWrite", "{\"todos\":[{\"content\":\"do it\"}]}", null);

    assertThat(insertedPane().path("elements").path(0).path("content").asString())
        .isEqualTo("> todos: [{\"content\":\"do it\"}]");
  }

  @Test
  @DisplayName("an empty input adds nothing under the line")
  void emptyInputAddsNothing() throws Exception {
    updater.setToolStatus("DateTime", "", null);

    final var pane = insertedPane();
    assertThat(title(pane)).isEqualTo("Tool calls \u2014 DateTime ...");
    assertThat(pane.path("elements").path(0).path("content").asString()).isEmpty();
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
