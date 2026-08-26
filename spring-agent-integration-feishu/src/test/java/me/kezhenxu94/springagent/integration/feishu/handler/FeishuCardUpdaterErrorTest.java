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
import tools.jackson.databind.json.JsonMapper;

/**
 * A run that fails partway has usually written most of an answer already, so what the card does
 * with the failure decides whether the reader keeps that answer or loses it.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterErrorTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @TempDir Path userHomeRoot;

  private FeishuCard card;
  private FeishuCardUpdater updater;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    updater =
        FeishuCardUpdater.forRun(
            card, new JsonMapper(), null, messages, cardElements(messages), null);
  }

  private String lastContentSent() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getValue().getContentCardElementReqBody().getContent();
  }

  @Test
  @DisplayName("the failure is appended below the answer, in a code block, not in place of it")
  void errorIsAppendedToTheAnswer() throws Exception {
    updater.onContent("the first half of an answer");
    updater.onError(new IllegalStateException("the model refused"));

    final var content = lastContentSent();
    assertThat(content).startsWith("the first half of an answer\n\n");
    assertThat(content).contains("Something went wrong: the model refused");
    assertThat(content).contains("\n```\njava.lang.IllegalStateException: the model refused\n");
    assertThat(content).endsWith("\n```");
  }

  @Test
  @DisplayName("a failure before any answer shows on its own, with no stray blank lines")
  void errorWithoutAnswerStandsAlone() throws Exception {
    updater.onError(new IllegalStateException("the model refused"));

    assertThat(lastContentSent()).startsWith("Something went wrong: the model refused\n\n```\n");
  }

  @Test
  @DisplayName("a subagent's panel keeps saying why it failed, without a trace under the report")
  void aFailedSubagentPanelSaysWhy() throws Exception {
    final var insert = new CreateCardElementResp();
    insert.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(insert);
    final var replaced = new UpdateCardElementResp();
    replaced.setCode(0);
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenReturn(replaced);
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
    final var panels = new FeishuSubagentPanel(new JsonMapper(), messages);
    panels.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    card.insertBeforeFooter(
        panels.forInsert("sub_1", "Reading the log", "Read the log and say what broke", null),
        "sub_1");
    final var subagent =
        FeishuCardUpdater.forSubagent(
            card,
            new JsonMapper(),
            null,
            messages,
            panels,
            "sub_1",
            "Reading the log",
            "Read the log and say what broke");

    subagent.onContent("half of what it found");
    subagent.onError(new IllegalStateException("the tool blew up"));
    subagent.onFinished(AgentOutcome.FAILED);

    // The panel is rewritten whole when a subagent ends, and what it is rewritten with has to
    // carry the failure the streamed content was showing until then.
    final var captor = ArgumentCaptor.forClass(UpdateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement()).update(captor.capture());
    final var panel = captor.getValue().getUpdateCardElementReqBody().getElement();
    assertThat(panel).contains("half of what it found");
    assertThat(panel).contains("Something went wrong: the tool blew up");
    // The trace stays in the log: a panel is a summary, and the card refuses an over-long element.
    assertThat(panel).doesNotContain("java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("content that arrives after a failure replaces it, rather than landing under it")
  void laterContentClearsTheFailure() throws Exception {
    updater.onContent("the first half of an answer");
    updater.onError(new IllegalStateException("the model refused"));
    updater.onContent("the first half of an answer, and the second");

    assertThat(lastContentSent()).isEqualTo("the first half of an answer, and the second");
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
