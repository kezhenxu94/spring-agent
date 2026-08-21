package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
  @Mock private UserWorkspaceFactory userWorkspaceFactory;

  private FeishuCardUpdater updater;

  @BeforeEach
  void setUp() throws Exception {
    final var ok = new ContentCardElementResp();
    ok.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenReturn(ok);
    updater =
        new FeishuCardUpdater(
            feishu,
            new JsonMapper(),
            "card-1",
            "ou_user",
            restTemplate,
            userWorkspaceFactory,
            null,
            new FeishuMessages(
                new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH)),
            null);
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
  @DisplayName("content that arrives after a failure replaces it, rather than landing under it")
  void laterContentClearsTheFailure() throws Exception {
    updater.onContent("the first half of an answer");
    updater.onError(new IllegalStateException("the model refused"));
    updater.onContent("the first half of an answer, and the second");

    assertThat(lastContentSent()).isEqualTo("the first half of an answer, and the second");
  }
}
