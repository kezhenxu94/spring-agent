package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a run does when the card it is writing to has not got the element the write names. Feishu
 * answers {@code 300315} where an element is created or replaced and {@code 300313} where one is
 * streamed into — one situation, two codes, because it is two APIs — and the streaming one is the
 * write a run makes on every chunk of its answer.
 *
 * <p>There are two ways a run comes to make such a write, and this covers both. An element can go
 * from under a run mid-turn, the card being Feishu's state and not the run's. And a card can fill
 * up with writes still queued for it: what is queued is sent one call at a time, so the first write
 * in a batch that fills the card takes the run onto another, and everything behind it names
 * elements that were on the card left behind.
 *
 * <p>The card here is modelled rather than stubbed a code at a time — it remembers what each card
 * carries and refuses a write naming anything else — because what has to hold is not that one code
 * is handled but that no write of the run's is ever refused for want of an element.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardStaleWriteTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  /** What each card carries. A card is created with the spend row and gains the rest as it goes. */
  private final Map<String, Set<String>> carrying = new ConcurrentHashMap<>();

  /** The cards Feishu is refusing writes to, as though each were over its size limit. */
  private final Set<String> full = Collections.synchronizedSet(new HashSet<>());

  /**
   * Every write refused for want of the element it named: which card, which element, which code.
   */
  private final List<String> refused = Collections.synchronizedList(new ArrayList<>());

  /** Every streaming write that landed: which card, which element, and what it said. */
  private final List<String[]> streamed = Collections.synchronizedList(new ArrayList<>());

  /** Every insert that landed: which card, which element, and the idempotency key it carried. */
  private final List<String[]> inserted = Collections.synchronizedList(new ArrayList<>());

  private ScheduledExecutorService clock;
  private FeishuMessages messages;

  @BeforeEach
  void setUp() throws Exception {
    carrying.put("card-1", elementSet(FeishuCardElements.USAGE));

    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenAnswer(
            call -> {
              final ContentCardElementReq request = call.getArgument(0);
              final var response = new ContentCardElementResp();
              if (full.contains(request.getCardId())) {
                return refuse(response, 200860, "card over max size");
              }
              if (!on(request.getCardId()).contains(request.getElementId())) {
                // What the streaming API answers, which is not what creating or replacing answers.
                return noSuchElement(response, 300313, request.getCardId(), request.getElementId());
              }
              streamed.add(
                  new String[] {
                    request.getCardId(),
                    request.getElementId(),
                    request.getContentCardElementReqBody().getContent()
                  });
              response.setCode(0);
              return response;
            });

    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            call -> {
              final CreateCardElementReq request = call.getArgument(0);
              final var body = request.getCreateCardElementReqBody();
              final var response = new CreateCardElementResp();
              if (full.contains(request.getCardId())) {
                return refuse(response, 200860, "card over max size");
              }
              if (!on(request.getCardId()).contains(body.getTargetElementId())) {
                return noSuchElement(
                    response, 300315, request.getCardId(), body.getTargetElementId());
              }
              for (final var element : om.readTree(body.getElements())) {
                collectIds(element, on(request.getCardId()));
                inserted.add(
                    new String[] {
                      request.getCardId(), element.path("element_id").asString(""), body.getUuid()
                    });
              }
              response.setCode(0);
              return response;
            });

    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            call -> {
              final UpdateCardElementReq request = call.getArgument(0);
              final var response = new UpdateCardElementResp();
              if (full.contains(request.getCardId())) {
                return refuse(response, 200860, "card over max size");
              }
              if (!on(request.getCardId()).contains(request.getElementId())) {
                return noSuchElement(response, 300315, request.getCardId(), request.getElementId());
              }
              collectIds(
                  om.readTree(request.getUpdateCardElementReqBody().getElement()),
                  on(request.getCardId()));
              response.setCode(0);
              return response;
            });

    clock = Executors.newScheduledThreadPool(1);
    messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
  }

  @AfterEach
  void tearDown() {
    clock.shutdownNow();
  }

  @Test
  @DisplayName("an element that has gone from under the run is put back on the next chunk")
  void putsBackAnElementAStreamingWriteFoundGone() {
    final var card = card(Duration.ZERO);
    final var run = FeishuCardUpdater.forRun(card, om, null, messages, elements(), null);

    run.onContent("one");
    assertThat(lastStreamed()[1]).isEqualTo(FeishuCardElements.MESSAGE);

    // The card is Feishu's state, and the run put this element there and watched it land: it goes
    // all the same — a card action's callback restores the card as it stood when it answered.
    on("card-1").remove(FeishuCardElements.MESSAGE);
    run.onContent("one two");
    assertThat(refused).containsExactly("card-1/message/300313");

    // Which used to be the answer lost on every chunk for the rest of the turn, the run having no
    // way to hear that the write was refused.
    run.onContent("one two three");
    assertThat(refused).containsExactly("card-1/message/300313");
    assertThat(lastStreamed()[1]).isEqualTo(FeishuCardElements.MESSAGE);
    assertThat(lastStreamed()[2]).contains("one two three");
  }

  @Test
  @DisplayName(
      "the insert that puts an element back is a different write from the one that put it there")
  void putsItBackUnderAKeyOfItsOwn() {
    final var card = card(Duration.ZERO);
    final var run = FeishuCardUpdater.forRun(card, om, null, messages, elements(), null);

    run.onContent("one");
    on("card-1").remove(FeishuCardElements.MESSAGE);
    run.onContent("one two");
    run.onContent("one two three");

    // The first insert landed, so a repeat of it under the same idempotency key is answered with
    // the success Feishu already gave and the element stays off the card for good.
    final var keys = keysFor("card-1", FeishuCardElements.MESSAGE);
    assertThat(keys).hasSize(2);
    assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
  }

  @Test
  @DisplayName(
      "what was queued for a card that filled up is not sent to the card the run moved onto")
  void doesNotWriteAQueuedChangeToTheCardItWasNotQueuedFor() {
    // An interval, so that writes pile up into a batch rather than each going out as it is made:
    // a batch is what can outlive the card it was queued for.
    final var card = card(Duration.ofMinutes(1));
    final var run = FeishuCardUpdater.forRun(card, om, null, messages, elements(), null);

    run.onContent("one");
    // Queued behind the interval, and about to be the write that fills the card up.
    full.add("card-1");
    run.onReasoning("and it thought about it");

    assertThat(card.cardId()).isEqualTo("card-2");
    // Nothing behind the write that filled the card was sent to the card the run moved onto, which
    // carries the spend row and nothing else: every one of them would have been refused.
    assertThat(refused).isEmpty();

    run.onFinished(AgentOutcome.COMPLETED);
    assertThat(refused).isEmpty();
    assertThat(streamed)
        .anyMatch(
            write ->
                write[0].equals("card-2")
                    && write[1].equals(FeishuCardElements.MESSAGE)
                    && write[2].contains("one"));
  }

  @Test
  @DisplayName(
      "the card the run moved onto gets one tool pane, not the stale one and one of its own")
  void putsOneToolPaneOnTheCardItMovedOnto() {
    final var card = card(Duration.ofMinutes(1));
    final var run = FeishuCardUpdater.forRun(card, om, null, messages, elements(), null);

    run.onContent("one");
    run.setToolStatus("Bash", "{\"command\":\"ls\"}", null);
    // A second call, so the pane is rewritten rather than inserted: it is that replacement, left
    // queued for the card that fills up, that used to put a second pane on the card moved onto —
    // put back there by the replacement's own recovery, under the key it was built with, and then
    // inserted again by the run, which had been told nothing and had a key of its own.
    run.setToolStatus("Read", "{\"path\":\"/etc/hosts\"}", null);
    full.add("card-1");
    run.onReasoning("and it thought about it");
    assertThat(card.cardId()).isEqualTo("card-2");

    // A call made since the run moved on, which is what the pane on this card is for: the ones
    // before it are on the card above and are counted rather than shown twice.
    run.setToolStatus("Grep", "{\"pattern\":\"why\"}", null);
    run.onFinished(AgentOutcome.COMPLETED);

    assertThat(refused).isEmpty();
    assertThat(keysFor("card-2", FeishuCardElements.TOOLS)).hasSize(1);
  }

  @Test
  @DisplayName("a run that fills its last card still finishes the card it ended on")
  void finishesTheCardTheRunEndedOn() {
    final var card = card(Duration.ofMinutes(1));
    final var run = FeishuCardUpdater.forRun(card, om, null, messages, elements(), null);

    run.onContent("one");
    full.add("card-1");
    run.onFinished(AgentOutcome.COMPLETED);

    // Finishing is about the card and not about an element of it, so it is the one thing a
    // rollover does not drop: a card left in streaming mode goes on saying it is being written to.
    assertThat(card.cardId()).isEqualTo("card-2");
    assertThat(refused).isEmpty();
  }

  // -----------------------------------------------------------------------------------------------

  private FeishuCard card(final Duration interval) {
    final var card =
        new FeishuCard(
            feishu,
            "card-1",
            null,
            new UserHome(userHomeRoot),
            messages,
            interval,
            0,
            clock,
            // On the calling thread, so that a write has been made by the time the call returns.
            Runnable::run);
    final var next = new AtomicInteger(1);
    card.continuation(
        fullCardId -> {
          final var continued = "card-" + next.incrementAndGet();
          carrying.put(continued, elementSet(FeishuCardElements.USAGE));
          return continued;
        });
    return card;
  }

  private FeishuCardElements elements() {
    return new FeishuCardElements(
        om, messages, new ClassPathResource("feishu/card-elements.json"), null);
  }

  private Set<String> on(final String cardId) {
    return carrying.computeIfAbsent(cardId, ignored -> elementSet());
  }

  private static Set<String> elementSet(final String... ids) {
    return Collections.synchronizedSet(new LinkedHashSet<>(List.of(ids)));
  }

  /** Every id in an element, its nested ones included: a panel arrives carrying its body. */
  private static void collectIds(final JsonNode element, final Set<String> onCard) {
    final var id = element.path("element_id").asString("");
    if (!id.isEmpty()) {
      onCard.add(id);
    }
    for (final var child : element.properties()) {
      if (child.getValue().isObject() || child.getValue().isArray()) {
        for (final var nested : child.getValue()) {
          collectIds(nested, onCard);
        }
      }
    }
  }

  private static <T extends BaseResponse> T refuse(
      final T response, final int code, final String message) {
    response.setCode(code);
    response.setMsg("ErrMsg: " + message);
    return response;
  }

  private <T extends BaseResponse> T noSuchElement(
      final T response, final int code, final String cardId, final String elementId) {
    refused.add(cardId + "/" + elementId + "/" + code);
    return refuse(response, code, "not find elementID : " + elementId);
  }

  private String[] lastStreamed() {
    assertThat(streamed).isNotEmpty();
    return streamed.get(streamed.size() - 1);
  }

  /** The idempotency key of every insert of one element onto one card, in the order they landed. */
  private List<String> keysFor(final String cardId, final String elementId) {
    return inserted.stream()
        .filter(insert -> insert[0].equals(cardId) && insert[1].equals(elementId))
        .map(insert -> insert[2])
        .toList();
  }
}
