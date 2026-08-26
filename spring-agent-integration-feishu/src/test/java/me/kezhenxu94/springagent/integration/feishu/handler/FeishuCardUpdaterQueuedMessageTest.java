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
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null));
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
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-1", "it should be kezhenxu94/spring-agent");

    assertThat(lastWrite().getElementId()).isEqualTo("queued");
    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .startsWith("> ")
        .contains("Queued: it should be kezhenxu94/spring-agent");

    updater.onQueuedMessageRead(List.of());

    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .isEqualTo("> <font color='grey'>Picked up: it should be kezhenxu94/spring-agent</font>");
  }

  @Test
  @DisplayName("every message gets a quoted line of its own, saying where each one stands")
  void oneLinePerMessage() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-2", "the first one");
    updater.onQueuedMessageRead(List.of());
    updater.onMessageQueued("m-3", "the second one");

    final var content = lastWrite().getContentCardElementReqBody().getContent();
    assertThat(content.lines())
        .containsExactly(
            "> <font color='grey'>Picked up: the first one</font>",
            "> <font color='grey'>Queued: the second one</font>");
  }

  @Test
  @DisplayName("a message written over several lines is folded onto the one line it is given")
  void multiLineMessageIsFolded() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-4", "  it should be\n   kezhenxu94/spring-agent  ");

    assertThat(lastWrite().getContentCardElementReqBody().getContent().lines()).hasSize(1);
    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .contains("Queued: it should be kezhenxu94/spring-agent");
  }

  @Test
  @DisplayName("a message a line cannot show is still said to have arrived")
  void aMessageWithNothingToShow() throws Exception {
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-5", null);

    assertThat(lastWrite().getContentCardElementReqBody().getContent())
        .contains(messages.get("card-message-unshown"));
  }

  @Test
  @DisplayName("the answer is put on the card first, being what the queued line is placed above")
  void theAnswerIsAddedBeforeTheLineThatAnchorsOnIt() throws Exception {
    // Both are elements the card gains on first use, and this one is placed above the answer — so a
    // message queued before the model has said anything has to add the answer's element itself.
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), null);

    updater.onMessageQueued("m-6", "it should be kezhenxu94/spring-agent");

    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    final var inserts = captor.getAllValues();
    assertThat(inserts).hasSize(2);
    assertThat(inserts.get(0).getCreateCardElementReqBody().getTargetElementId()).isEqualTo("stop");
    assertThat(inserts.get(0).getCreateCardElementReqBody().getElements())
        .contains("\"element_id\":\"message\"");
    assertThat(inserts.get(1).getCreateCardElementReqBody().getTargetElementId())
        .isEqualTo("message");
  }

  @Test
  @DisplayName("the message itself is marked, as seen and then as taken in")
  void theMessageIsMarkedWhereItWasSent() throws Exception {
    // The card says the same thing, but the card is a different message from theirs and on a phone
    // it is often not the one on screen. A reaction is where the person who just typed is looking.
    final var reactions = org.mockito.Mockito.mock(FeishuMessageReactions.class);
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), reactions);

    updater.onMessageQueued("om-1", "it should be kezhenxu94/spring-agent");

    verify(reactions).queued("om-1");
    verify(reactions, org.mockito.Mockito.never()).read(org.mockito.ArgumentMatchers.anyString());

    updater.onQueuedMessageRead(List.of("om-1"));

    verify(reactions).read("om-1");
  }

  @Test
  @DisplayName("a message the run never managed to read is not marked as taken in")
  void anUnreadMessageIsNotMarkedRead() throws Exception {
    // A message whose text could not be produced is dropped rather than failing the run, and is
    // not among the ids handed over — marking it read would be saying something false about it.
    final var reactions = org.mockito.Mockito.mock(FeishuMessageReactions.class);
    final var updater =
        FeishuCardUpdater.forRun(card, om, null, messages, cardElements(messages), reactions);

    updater.onMessageQueued("om-1", "the one that was read");
    updater.onMessageQueued("om-2", "the one that was dropped");
    updater.onQueuedMessageRead(List.of("om-1"));

    verify(reactions).read("om-1");
    verify(reactions, org.mockito.Mockito.never()).read("om-2");
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

    updater.onMessageQueued("m-7", "it should be kezhenxu94/spring-agent");
    updater.onQueuedMessageRead(List.of());

    verify(feishu.cardkit().v1().cardElement(), org.mockito.Mockito.never())
        .content(any(ContentCardElementReq.class));
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
