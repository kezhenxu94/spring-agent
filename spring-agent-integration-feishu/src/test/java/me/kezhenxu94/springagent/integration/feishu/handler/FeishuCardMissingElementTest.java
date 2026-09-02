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
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * What a run does when the card turns out not to have an element the run put there and watched
 * land. Feishu answers {@code 300315} to a write naming it, and the run has no way of knowing
 * beforehand: the card is Feishu's state, and an element can go from under a run mid-turn.
 *
 * <p>It matters most for the answer, which is anchored on whatever of the card's elements is below
 * it — the tool pane, whenever the model calls a tool before it says a word. The answer is put on
 * the card once and streamed into from then on, so an anchor that has gone used to cost the reader
 * every word of the turn from that point, on every chunk, with nothing to bring it back.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardMissingElementTest {

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

  /** The elements this card claims not to have, whichever write names one of them. */
  private final Set<String> missing = new java.util.HashSet<>();

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
              return resp(new CreateCardElementResp(), body.getTargetElementId());
            });
    updater =
        FeishuCardUpdater.forRun(
            new FeishuCard(feishu, "card-1", restTemplate, new UserHome(userHomeRoot), messages),
            om,
            null,
            messages,
            cardElements(messages),
            null);
  }

  private <T extends com.lark.oapi.core.response.BaseResponse> T resp(
      final T response, final String elementId) {
    if (missing.contains(elementId)) {
      response.setCode(300315);
      response.setMsg("ErrMsg: msg: [not find elementID : " + elementId + "]");
    } else {
      response.setCode(0);
    }
    return response;
  }

  @Test
  @DisplayName("an answer whose anchor has gone from the card is put above the footer instead")
  void theAnswerLandsWhenItsAnchorIsGone() throws Exception {
    // A model that calls a tool before it says a word, which is what puts the answer's anchor on
    // the tool pane rather than on the spend row.
    updater.setToolStatus("Bash", "{\"command\":\"ls -la\"}", null);
    assertThat(insertTargets()).containsExactly("usage");

    // And a card that no longer has that pane by the time the model does speak.
    missing.add("tools");
    updater.onContent("the answer");

    // Asked for above the pane, refused, and put above the spend row rather than lost.
    assertThat(insertTargets()).containsExactly("usage", "tools", "usage");
    assertThat(insertedIds()).containsExactly("tools", "message", "message");
    // Which is the whole point: the element is there, so the answer streams into it.
    assertThat(streamedInto()).contains("message");
  }

  @Test
  @DisplayName("an element that is not there to be replaced is put back on the card")
  void aMissingElementIsPutBack() throws Exception {
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            call ->
                resp(
                    new UpdateCardElementResp(),
                    call.<UpdateCardElementReq>getArgument(0).getElementId()));

    updater.setToolStatus("Bash", "{\"command\":\"ls -la\"}", null);
    // The pane is replaced whole from the second call on, and this card has lost it in between.
    missing.add("tools");
    updater.setToolStatus("Read", "{\"path\":\"/etc/hosts\"}", null);

    // The replacement carries the whole element, so it can go back on rather than be reported.
    assertThat(insertTargets()).containsExactly("usage", "usage");
    assertThat(insertedIds()).containsExactly("tools", "tools");
  }

  @Test
  @DisplayName("a card that has lost the spend row is given up on rather than written to for ever")
  void thereIsNothingToAnchorOnWhenTheFooterIsGone() throws Exception {
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            call ->
                resp(
                    new UpdateCardElementResp(),
                    call.<UpdateCardElementReq>getArgument(0).getElementId()));

    // The spend row is the one element every card is created carrying, so it is where a write with
    // nowhere else to go ends up. A card without it has nowhere at all.
    missing.add("usage");
    updater.onModel("some-model");

    verify(feishu.cardkit().v1().cardElement(), never()).create(any(CreateCardElementReq.class));
  }

  /** What each insert of the turn named as the element it lands above, oldest first. */
  private List<String> insertTargets() throws Exception {
    return inserts().stream()
        .map(insert -> insert.getCreateCardElementReqBody().getTargetElementId())
        .toList();
  }

  /** The id of the one element each insert carried, oldest first. */
  private List<String> insertedIds() throws Exception {
    return inserts().stream()
        .map(
            insert ->
                om.readTree(insert.getCreateCardElementReqBody().getElements())
                    .path(0)
                    .path("element_id")
                    .asString())
        .toList();
  }

  private List<CreateCardElementReq> inserts() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).create(captor.capture());
    return captor.getAllValues();
  }

  private List<String> streamedInto() throws Exception {
    final var captor = ArgumentCaptor.forClass(ContentCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement(), atLeastOnce()).content(captor.capture());
    return captor.getAllValues().stream().map(ContentCardElementReq::getElementId).toList();
  }

  private static FeishuCardElements cardElements(final FeishuMessages messages) {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
