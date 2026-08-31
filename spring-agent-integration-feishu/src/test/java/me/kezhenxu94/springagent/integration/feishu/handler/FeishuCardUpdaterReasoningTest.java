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
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.usermodels.ReasoningEfforts;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
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

    // The panel the run streams into, closed — working-out is behind a chevron rather than in
    // front of the answer — and titled in the workspace's language.
    final var panel = insertOf("reasoning");
    assertThat(panel).contains("\"tag\":\"collapsible_panel\"").contains("\"expanded\":false");
    assertThat(panel).contains("\"element_id\":\"reasoning_body\"");
    assertThat(panel).contains(messages.get("card-reasoning"));
  }

  @Test
  @DisplayName("the pane is left as the reader had it, all the way to the end of the run")
  void thePaneIsNeverRewritten() throws Exception {
    // It is born closed, so there is nothing to fold away — and a run that rewrote it to close it
    // would snap it shut under a reader who had opened it, which Feishu gives no way to notice.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("Thinking about it.");
    updater.onContent("Here you go.");
    updater.onFinished(AgentOutcome.COMPLETED);

    assertThat(replacements()).isEmpty();
  }

  @Test
  @DisplayName("the title says how hard the model was asked to think")
  void theTitleCarriesTheEffort() throws Exception {
    // On the panel rather than on the spend line: it is a fact about the thinking, and it is what
    // tells a reader what is behind the chevron before they open it — the same job the count does
    // on the knowledge-sources panel.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages, "xhigh"), null);

    updater.onReasoning("Thinking about it.");

    // Inside the colour the title is set in, not after it, or a grey title would be followed by a
    // black bracket.
    assertThat(insertOf("reasoning"))
        .contains("<font color='grey'>" + messages.get("card-reasoning") + "(xhigh)</font>");
  }

  @Test
  @DisplayName(
      "the title says how hard the user's own model was asked to think, not the deployment")
  void theTitleCarriesTheUsersEffort() throws Exception {
    // The label would otherwise report a process-wide property at a run that had nothing to do with
    // it: a user who chose minimal would watch a card claim the deployment's xhigh.
    final var elements = cardElements(messages, "xhigh");
    elements.userChatClients = chatClientsWhere("u1", "minimal");

    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, elements, null, "u1");

    updater.onReasoning("Thinking about it.");

    assertThat(insertOf("reasoning"))
        .contains("<font color='grey'>" + messages.get("card-reasoning") + "(minimal)</font>");
  }

  @Test
  @DisplayName("a user who turned the parameter off gets no brackets, not the deployment's effort")
  void theTitleHonoursNotSent() throws Exception {
    final var elements = cardElements(messages, "xhigh");
    elements.userChatClients = chatClientsWhere("u1", ReasoningEfforts.NOT_SENT);

    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, elements, null, "u1");

    updater.onReasoning("Thinking about it.");

    assertThat(insertOf("reasoning"))
        .contains("<font color='grey'>" + messages.get("card-reasoning") + "</font>");
  }

  /** A resolver that says this user is on the application's model with an effort of their own. */
  private static UserChatClients chatClientsWhere(final String userId, final String effort) {
    final var row =
        UserModelConfig.builder()
            .id(UserModelConfig.idFor(userId, "@"))
            .ownerId(userId)
            .name("@")
            .reasoningEffort(effort)
            .activated(true)
            .build();
    final var repo = org.mockito.Mockito.mock(UserModelConfigRepo.class);
    org.mockito.Mockito.when(repo.findByOwnerId(userId)).thenReturn(List.of(row));
    final var appModel =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl("https://app/v1")
                    .apiKey("k")
                    .model("app-model")
                    .reasoningEffort("xhigh")
                    .build())
            .build();
    return new UserChatClients(
        ChatClient.builder(appModel).build(),
        new UserModelRegistry(
            repo,
            new AesGcmSealer(java.util.Base64.getEncoder().encodeToString(new byte[32]), "test"),
            3),
        appModel,
        List.of(),
        4);
  }

  @Test
  @DisplayName("a deployment that states no effort gets a title with no empty brackets on it")
  void noEffortNoBrackets() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onReasoning("Thinking about it.");

    assertThat(insertOf("reasoning"))
        .contains("<font color='grey'>" + messages.get("card-reasoning") + "</font>");
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

    assertThat(anchors()).containsExactly("usage", "usage");
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
    assertThat(anchors()).containsExactly("usage", "usage", "reasoning");
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
    assertThat(anchors()).containsExactly("usage", "message", "message");
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
    // The footer is the spend row and, below it, the conversation hint — both there from the
    // moment the card is sent. Anything added mid-run has to be placed above the spend row and not
    // merely above the hint, or it comes to rest inside the footer.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onModel("the-model");
    updater.handle(new Todos(List.of(new TodoItem("read it", Status.pending, "reading it"))));
    card.insertBeforeFooter("[{\"tag\":\"markdown\",\"content\":\"a panel\"}]", "panel-1");

    // The task list and the panel above the spend row, not under it.
    assertThat(anchors()).containsExactly("usage", "usage");
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
            null,
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
    return cardElements(messages, null);
  }

  /** The same, for a deployment that states how hard the model should think. */
  private static FeishuCardElements cardElements(
      final FeishuMessages messages, final String effort) {
    final OpenAiChatProperties chatProperties;
    if (effort == null) {
      chatProperties = null;
    } else {
      chatProperties = new OpenAiChatProperties();
      chatProperties.setReasoningEffort(effort);
    }
    return new FeishuCardElements(
        new JsonMapper(),
        messages,
        new ClassPathResource("feishu/card-elements.json"),
        chatProperties);
  }
}
