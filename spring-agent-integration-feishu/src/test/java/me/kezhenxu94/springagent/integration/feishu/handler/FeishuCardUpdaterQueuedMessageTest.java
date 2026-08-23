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
import java.nio.file.Path;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the card says about a message the user sent while the run was working. Its own element,
 * deliberately: written under the answer it would be gone with the next streaming tick, which
 * rewrites that element whole.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterQueuedMessageTest {

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
    // The card gains the element the first time there is a message to put in it.
    final var inserted = new CreateCardElementResp();
    inserted.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenReturn(inserted);
    messages =
        new FeishuMessages(
            new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH, null));
    card = new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages);
    om = new JsonMapper();
  }

  private ContentCardElementReq lastWrite() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("a message waiting is quoted on the card, and then said to have been picked up")
  void queuedThenRead() throws Exception {
    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, cardElements());

    updater.onMessageQueued("it should be kezhenxu94/spring-agent");

    assertThat(lastWrite().getElementId()).isEqualTo("queued");
    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .startsWith("> ")
        .contains("Queued: it should be kezhenxu94/spring-agent");

    updater.onQueuedMessageRead();

    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .isEqualTo("> <font color='grey'>Picked up: it should be kezhenxu94/spring-agent</font>");
  }

  @Test
  @DisplayName("every message gets a quoted line of its own, saying where each one stands")
  void oneLinePerMessage() throws Exception {
    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, cardElements());

    updater.onMessageQueued("the first one");
    updater.onQueuedMessageRead();
    updater.onMessageQueued("the second one");

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content.lines())
        .containsExactly(
            "> <font color='grey'>Picked up: the first one</font>",
            "> <font color='grey'>Queued: the second one</font>");
  }

  @Test
  @DisplayName("a message written over several lines is folded onto the one line it is given")
  void multiLineMessageIsFolded() throws Exception {
    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, cardElements());

    updater.onMessageQueued("  it should be\n   kezhenxu94/spring-agent  ");

    assertThat(lastWrite().getContentCardElementReqBody().getContent().lines()).hasSize(1);
    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .contains("Queued: it should be kezhenxu94/spring-agent");
  }

  @Test
  @DisplayName("a message a line cannot show is still said to have arrived")
  void aMessageWithNothingToShow() throws Exception {
    final var updater = FeishuCardUpdater.forRun(card, om, null, messages, cardElements());

    updater.onMessageQueued(null);

    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .contains(messages.get("card-message-unshown"));
  }

  @Test
  @DisplayName("a subagent has nowhere to say it, and says nothing")
  void aSubagentSaysNothing() throws Exception {
    // A subagent is not the run the user is replying to — that is the run that started it — and its
    // panel has no element for this. Writing anywhere else on the card would overwrite a panel.
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

    updater.onMessageQueued("it should be kezhenxu94/spring-agent");
    updater.onQueuedMessageRead();

    verify(feishu.cardkit().v1().cardElement(), org.mockito.Mockito.never())
        .content(any(ContentCardElementReq.class));
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements() {
    final var elements = new FeishuCardElements(new JsonMapper());
    elements.cardElements = new ClassPathResource("feishu/card-elements.json");
    return elements;
  }
}
