package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
 * The run's task list: a panel like the thinking, tool and knowledge-sources panes beside it, with
 * how many tasks are on it in the title.
 *
 * <p>What is worth asserting here is that the panel is rewritten whole as the list changes rather
 * than streamed into. The count is in the title, and a title is the one part of a panel a stream
 * cannot reach — so a task list that grew by streaming would carry a count from its first write for
 * the rest of the run.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterTodoTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuMessages messages;
  private FeishuCard card;
  private JsonMapper om;

  @BeforeEach
  void setUp() throws Exception {
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    final var updated = new UpdateCardElementResp();
    updated.setCode(0);
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenReturn(updated);
    messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    om = new JsonMapper();
  }

  @Test
  @DisplayName("the list goes in a panel of its own, open, titled by how many tasks it holds")
  void theListIsAPanel() throws Exception {
    updater()
        .handle(
            new Todos(
                List.of(
                    new TodoItem("read it", Status.completed, "reading it"),
                    new TodoItem("write it", Status.in_progress, "writing it"),
                    new TodoItem("check it", Status.pending, "checking it"))));

    final var panel = insertOf("todo");
    assertThat(panel).contains("\"tag\":\"collapsible_panel\"");
    // Open, unlike the panels around it: a task list is what the run means to do next, which is
    // what a reader watching a long run comes back for.
    assertThat(panel).contains("\"expanded\":true");
    // Titled the way every other panel on the card is: the bold name of the section, then what is
    // behind the chevron in brackets, inside the colour the title is set in.
    assertThat(panel)
        .contains("<font color='grey'>" + messages.get("card-todo") + "(3)</font>")
        .doesNotContain("</font>(3)");
    // And the tasks themselves inside it, the one in hand emphasised by its active form.
    assertThat(panel).contains("☑ read it").contains("☒ **writing it**").contains("☐ check it");
  }

  @Test
  @DisplayName("a list that changes is rewritten whole, so its count keeps up with it")
  void theCountFollowsTheList() throws Exception {
    final var updater = updater();

    updater.handle(new Todos(List.of(new TodoItem("read it", Status.pending, "reading it"))));
    updater.handle(
        new Todos(
            List.of(
                new TodoItem("read it", Status.completed, "reading it"),
                new TodoItem("write it", Status.in_progress, "writing it"))));

    // Once on the card, the panel is replaced rather than added again — and never streamed into,
    // which would leave the title saying one task while the panel held two.
    assertThat(insertedElements()).containsExactly("todo");
    verify(feishu.cardkit().v1().cardElement(), never()).content(any(ContentCardElementReq.class));
    assertThat(replacements()).hasSize(1);
    assertThat(replacements().get(0)).contains(messages.get("card-todo") + "(2)");
  }

  @Test
  @DisplayName("each rewrite is its own, so a retry cannot make Feishu drop one")
  void everyRewriteHasItsOwnKey() throws Exception {
    final var updater = updater();

    updater.handle(new Todos(List.of(new TodoItem("read it", Status.pending, "reading it"))));
    updater.handle(new Todos(List.of(new TodoItem("read it", Status.completed, "reading it"))));
    updater.handle(new Todos(List.of(new TodoItem("write it", Status.pending, "writing it"))));

    assertThat(updateKeys()).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("a run that writes an empty list is given no panel to hold it")
  void nothingToShowNothingAdded() throws Exception {
    updater().handle(new Todos(List.of()));

    verify(feishu.cardkit().v1().cardElement(), never()).create(any(CreateCardElementReq.class));
  }

  @Test
  @DisplayName("a subagent has no task list of its own, and writes none")
  void aSubagentHasNoPanelForIt() throws Exception {
    FeishuCardUpdater.forSubagent(
            card,
            om,
            null,
            messages,
            new FeishuSubagentPanel(om, messages),
            "sub-1",
            "reading",
            "read it")
        .handle(new Todos(List.of(new TodoItem("read it", Status.pending, "reading it"))));

    verify(feishu.cardkit().v1().cardElement(), never()).create(any(CreateCardElementReq.class));
  }

  private FeishuCardUpdater updater() {
    return FeishuCardUpdater.forRun(
        card,
        om,
        null,
        messages,
        new FeishuCardElements(
            new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null),
        null);
  }

  private List<CreateCardElementReq> inserts() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    return captor.getAllValues();
  }

  private List<String> insertedElements() throws Exception {
    return inserts().stream()
        .map(insert -> insert.getCreateCardElementReqBody().getUuid())
        .map(uuid -> uuid.substring(uuid.indexOf(':') + 1))
        .toList();
  }

  private String insertOf(final String elementId) throws Exception {
    return inserts().stream()
        .filter(insert -> insert.getCreateCardElementReqBody().getUuid().endsWith(":" + elementId))
        .map(insert -> insert.getCreateCardElementReqBody().getElements())
        .findFirst()
        .orElseThrow();
  }

  private List<UpdateCardElementReq> updates() throws Exception {
    final var captor = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).update(captor.capture());
    return captor.getAllValues();
  }

  /** The panel as it was rewritten, which is the only way its count can change. */
  private List<String> replacements() throws Exception {
    return updates().stream()
        .filter(update -> "todo".equals(update.getElementId()))
        .map(update -> update.getUpdateCardElementReqBody().getElement())
        .toList();
  }

  /** The idempotency key each rewrite was sent with. */
  private List<String> updateKeys() throws Exception {
    return updates().stream()
        .map(update -> update.getUpdateCardElementReqBody().getUuid())
        .toList();
  }
}
