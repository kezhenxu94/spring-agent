package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import java.nio.file.Path;
import java.util.List;
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
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the model thought on its way to the answer, in a panel of its own.
 *
 * <p>Most of what is worth asserting here is where the panel lands. Three elements want the same
 * stretch of card — what the user said mid-run at the top, then the thinking, then the answer — and
 * an insert names one element and lands immediately above it, so the order they end up in depends
 * on the order they arrive in unless the anchors move. These tests are that ordering, arrived at
 * both ways round.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterReasoningTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuMessages messages;
  private FeishuCard card;
  private JsonMapper om;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    om = new JsonMapper();
  }

  @Test
  @DisplayName("the thinking goes in a panel of its own, not in the answer")
  void thinkingGoesInItsOwnPanel() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("The user asked for the repository, so I should look it up.");

    final var write = lastWrite();
    assertThat(write.getElementId()).isEqualTo("reasoning_body");
    assertThat(write.getContentCardElementReqBody().getContent())
        .isEqualTo("The user asked for the repository, so I should look it up.");

    // The panel the run streams into, open — the thinking is happening as it arrives — and titled
    // in the workspace's language.
    final var panel = insertOf("reasoning");
    assertThat(panel).contains("\"tag\":\"collapsible_panel\"").contains("\"expanded\":true");
    assertThat(panel).contains("\"element_id\":\"reasoning_body\"");
    assertThat(panel).contains(messages.get("card-reasoning"));
  }

  @Test
  @DisplayName("the pane is folded away when the run ends, with the thinking still in it")
  void thePaneIsClosedAtTheEnd() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("Thinking about it.");
    updater.onContent("Here you go.");

    // Open for the length of the run: nothing rewrites it while the run is going.
    assertThat(replacements()).isEmpty();

    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(replacements()).hasSize(1);
    assertThat(replacements().get(0))
        .contains("\"expanded\":false")
        .contains("Thinking about it.")
        .contains("\"element_id\":\"reasoning_body\"");
  }

  @Test
  @DisplayName("a run that never thought has no pane to fold away")
  void nothingToCloseWithoutAPane() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onContent("Here you go.");
    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(replacements()).isEmpty();
  }

  @Test
  @DisplayName("thinking before the model has answered puts the panel above where the answer goes")
  void theThinkingComesBeforeTheAnswer() throws Exception {
    // Thinking arrives before the first word of the answer, so there is no answer element to anchor
    // on yet. The panel takes the stop button instead, which every card has from the moment it is
    // sent and which the answer is itself placed above — so the answer still lands below the panel.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("Thinking about it.");
    updater.onContent("Here you go.");

    assertThat(anchors()).containsExactly("stop", "stop");
    assertThat(insertedElements()).containsExactly("reasoning", "message");
  }

  @Test
  @DisplayName("what the user said mid-run stays above the thinking, whichever arrives first")
  void queuedMessagesStayAtTheTop() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("Thinking about it.");
    updater.onMessageQueued("m-1", "actually, the other repository");

    // The answer is added by the queued line, which is placed above it; the queued line then
    // anchors on the panel rather than the answer, which is what keeps it at the very top.
    assertThat(insertedElements()).containsExactly("reasoning", "message", "queued");
    assertThat(anchors()).containsExactly("stop", "stop", "reasoning");
  }

  @Test
  @DisplayName("thinking that arrives after a queued message still lands below it")
  void thinkingAfterAQueuedMessage() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-2", "actually, the other repository");
    updater.onReasoning("Thinking about it.");

    // The answer is on the card by now, so the panel anchors on it and lands between the two.
    assertThat(insertedElements()).containsExactly("message", "queued", "reasoning");
    assertThat(anchors()).containsExactly("stop", "message", "message");
  }

  @Test
  @DisplayName("a turn on an endpoint that reports no thinking never carries the panel")
  void noThinkingNoPanel() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("");
    updater.onContent("Here you go.");

    assertThat(insertedElements()).containsExactly("message");
  }

  @Test
  @DisplayName("everything added mid-run clears the footer, which grows as the run spends")
  void theFooterKeepsItsPlaceAtTheBottom() throws Exception {
    // The footer is the conversation hint, joined from above by the spend line once the run has
    // named a model. Anything added after that has to be placed above the spend line and not
    // merely above the hint, or it comes to rest inside the footer.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onModel("the-model");
    updater.handle(new Todos(List.of(new TodoItem("read it", Status.pending, "reading it"))));
    card.insertBeforeFooter("[{\"tag\":\"markdown\",\"content\":\"a panel\"}]", "panel-1");

    // usage above the hint; the task list and the panel above the usage line, not under it.
    assertThat(anchors()).containsExactly("guide", "usage", "usage");
  }

  @Test
  @DisplayName("a subagent's thinking has nowhere to go, and goes nowhere")
  void aSubagentHasNoPanelForIt() throws Exception {
    // A subagent writes into a panel that arrived complete. Streaming into the run's own reasoning
    // panel would show the subagent's thinking as the thinking of the run waiting on it.
    final var updater =
        FeishuCardUpdater.forSubagent(
            card,
            om,
            null,
            messages,
            new FeishuSubagentPanel(om, messages),
            "sub-1",
            "reading",
            "read it");

    updater.onReasoning("Thinking about it.");

    verify(feishu.cardkit().v1().cardElement(), never()).content(any(ContentCardElementReq.class));
  }

  /** The pane rewritten whole, which is the only way its chevron can change. */
  private List<String> replacements() throws Exception {
    final var captor = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), org.mockito.Mockito.atLeast(0))
        .update(captor.capture());
    return captor.getAllValues().stream()
        .filter(update -> "reasoning".equals(update.getElementId()))
        .map(update -> update.getUpdateCardElementReqBody().getElement())
        .toList();
  }

  private ContentCardElementReq lastWrite() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getValue();
  }

  private List<CreateCardElementReq> inserts() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    return captor.getAllValues();
  }

  /** The elements added to the card, in the order they were added. */
  private List<String> insertedElements() throws Exception {
    return inserts().stream()
        .map(insert -> insert.getCreateCardElementReqBody().getUuid())
        .map(uuid -> uuid.substring(uuid.indexOf(':') + 1))
        .toList();
  }

  /** What each of them was placed above. */
  private List<String> anchors() throws Exception {
    return inserts().stream()
        .map(insert -> insert.getCreateCardElementReqBody().getTargetElementId())
        .toList();
  }

  private String insertOf(final String elementId) throws Exception {
    return inserts().stream()
        .filter(insert -> insert.getCreateCardElementReqBody().getUuid().endsWith(":" + elementId))
        .map(insert -> insert.getCreateCardElementReqBody().getElements())
        .findFirst()
        .orElseThrow();
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
