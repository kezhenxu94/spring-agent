package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementResp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * A card holds 30KB and a turn holds as much as the model has to say, so a run outgrowing its card
 * is ordinary rather than exceptional: Feishu refuses the write with {@code 200860} and the run
 * carries on on another card, as many times over as it takes.
 *
 * <p>What is asserted here is the whole of what makes that work: that the run moves on rather than
 * stopping, that it goes on moving on — a fourth card is no different from a second — that it puts
 * its elements back on the card it moved to and continues its answer there instead of repeating it,
 * and that a write no card could ever hold does not reply a card per attempt.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardContinuationTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private FeishuCard card;

  /** The cards Feishu is refusing writes to, as though each were over its size limit. */
  private final Set<String> full = Collections.synchronizedSet(new HashSet<>());

  /** Every card the run was given to carry on writing to, in the order it was given them. */
  private final List<String> continuedOnto = Collections.synchronizedList(new ArrayList<>());

  /** Every streaming write that landed: which card, which element, and what it said. */
  private final List<String[]> streamed = Collections.synchronizedList(new ArrayList<>());

  /** Every element that was put on a card, and which card it went on. */
  private final List<String[]> inserted = Collections.synchronizedList(new ArrayList<>());

  /** Every element rewritten whole: which card, which element, and what it was rewritten to. */
  private final List<String[]> replaced = Collections.synchronizedList(new ArrayList<>());

  private FeishuMessages messages;

  @BeforeEach
  void setUp() throws Exception {
    final var refused = new ContentCardElementResp();
    refused.setCode(200860);
    refused.setMsg("ErrMsg: card over max size");
    final var streamedOk = new ContentCardElementResp();
    streamedOk.setCode(0);
    when(feishu.cardkit().v1().cardElement().content(any(ContentCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final ContentCardElementReq request = invocation.getArgument(0);
              if (full.contains(request.getCardId())) {
                return refused;
              }
              streamed.add(
                  new String[] {
                    request.getCardId(),
                    request.getElementId(),
                    request.getContentCardElementReqBody().getContent()
                  });
              return streamedOk;
            });

    final var insertRefused = new CreateCardElementResp();
    insertRefused.setCode(200860);
    insertRefused.setMsg("ErrMsg: card over max size");
    final var insertedOk = new CreateCardElementResp();
    insertedOk.setCode(0);
    when(feishu.cardkit().v1().cardElement().create(any(CreateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final CreateCardElementReq request = invocation.getArgument(0);
              if (full.contains(request.getCardId())) {
                return insertRefused;
              }
              inserted.add(
                  new String[] {
                    request.getCardId(),
                    request.getCreateCardElementReqBody().getTargetElementId(),
                    request.getCreateCardElementReqBody().getElements()
                  });
              return insertedOk;
            });

    final var replacedOk = new UpdateCardElementResp();
    replacedOk.setCode(0);
    when(feishu.cardkit().v1().cardElement().update(any(UpdateCardElementReq.class)))
        .thenAnswer(
            invocation -> {
              final UpdateCardElementReq request = invocation.getArgument(0);
              replaced.add(
                  new String[] {
                    request.getCardId(),
                    request.getElementId(),
                    request.getUpdateCardElementReqBody().getElement()
                  });
              return replacedOk;
            });

    messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    card = new FeishuCard(feishu, "card-1", null, new UserHome(userHomeRoot), messages);
    final var next = new AtomicInteger(1);
    card.continuation(
        fullCardId -> {
          final var continued = "card-" + next.incrementAndGet();
          continuedOnto.add(continued);
          return continued;
        });
  }

  @Test
  @DisplayName("a run whose card fills up carries on writing to the next one, and the next")
  void continuesOntoAsManyCardsAsItTakes() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onContent("first");
    assertThat(card.cardId()).isEqualTo("card-1");

    // Three cards filled in turn: the run is expected to go on moving, not to move once. Each is
    // written to before it is filled, since a card that has never taken a write is one no write
    // would fit on — which is a different thing and is not continued.
    full.add("card-1");
    run.onContent("first second");
    run.onContent("first second third");
    full.add("card-2");
    run.onContent("first second third fourth");
    run.onContent("first second third fourth fifth");
    full.add("card-3");
    run.onContent("first second third fourth fifth sixth");
    run.onContent("first second third fourth fifth sixth seventh");

    assertThat(continuedOnto).containsExactly("card-2", "card-3", "card-4");
    assertThat(card.cardId()).isEqualTo("card-4");
  }

  @Test
  @DisplayName("the card a run moves onto gets the elements the one it left had")
  void putsItsElementsBackOnTheCardItMovedOnto() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onContent("first");
    assertThat(inserted).anyMatch(insert -> insert[0].equals("card-1"));

    full.add("card-1");
    run.onContent("first second");
    run.onContent("first second third");

    // Nothing carried the element over, so the run added it again where it is now writing.
    assertThat(inserted).anyMatch(insert -> insert[0].equals("card-2"));
    assertThat(lastStreamed()[0]).isEqualTo("card-2");
    assertThat(lastStreamed()[1]).isEqualTo(FeishuCardElements.MESSAGE);
  }

  @Test
  @DisplayName("the answer continues on the new card rather than being written out again")
  void continuesTheAnswerRatherThanRepeatingIt() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onContent("what the run said on the card it filled");
    full.add("card-1");
    run.onContent("what the run said on the card it filled, and a little more");
    run.onContent("what the run said on the card it filled, and a little more, and more again");

    final var continued = lastStreamed()[2];
    // The reader has the first half above, on the card that filled up, so the card the run moved
    // onto says only what has been said since — and says that it is a continuation.
    assertThat(continued).doesNotContain("what the run said on the card it filled");
    assertThat(continued).contains(", and more again");
    assertThat(continued).contains(messages.get("card-continued").strip());
  }

  @Test
  @DisplayName("a write no card could hold does not reply a card for every attempt")
  void refusesToContinueACardThatHasTakenNothing() {
    full.add("card-1");
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onContent("more than any card can hold");
    run.onContent("more than any card can hold, and then some");

    // Nothing has ever fitted on this card, so a bigger card is not what is missing: continuing
    // would send a card per chunk and fill each one with the write that filled the last.
    assertThat(continuedOnto).isEmpty();
    assertThat(card.cardId()).isEqualTo("card-1");
  }

  @Test
  @DisplayName("the chunk that filled a card up is written onto the card the run finishes on")
  void writesWhatFilledTheCardUpOntoTheOneItFinishedOn() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onContent("what the run said");
    full.add("card-1");
    // The last thing the run says, refused because it is what takes the card over its limit: the
    // run has nothing more to stream, so unless finishing writes it, it is on no card at all.
    run.onContent("what the run said, and how it ended");
    run.onFinished(AgentOutcome.COMPLETED);

    assertThat(lastStreamed()[0]).isEqualTo("card-2");
    assertThat(lastStreamed()[2]).contains(", and how it ended");
  }

  @Test
  @DisplayName("a subagent's panel goes back onto the card the run moved onto")
  void putsASubagentPanelBackOnTheCardItMovedOnto() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);
    final var panels = new FeishuSubagentPanel(new JsonMapper(), messages);
    panels.subagentPanel = new ClassPathResource("feishu/subagent-panel.json");
    final var subagent =
        FeishuCardUpdater.forSubagent(
            card, new JsonMapper(), null, messages, panels, run, "sub_1", "Reading", "Read it");
    assertThat(subagent.insertPanel()).isTrue();

    run.onContent("the run says something");
    full.add("card-1");
    run.onContent("the run says something that does not fit");
    // The panel was left on the card that filled up, so the subagent has nowhere to write until it
    // is put on the card the run moved onto — which is the subagent's own next write to do.
    subagent.onContent("and the subagent reports");

    assertThat(lastStreamed()[0]).isEqualTo("card-2");
    assertThat(lastStreamed()[1]).isEqualTo(FeishuSubagentPanel.bodyElementId("sub_1"));
    assertThat(lastStreamed()[2]).contains("and the subagent reports");
  }

  @Test
  @DisplayName("the thinking continues on the new card rather than being written out again")
  void continuesTheThinkingRatherThanRepeatingIt() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onReasoning("what the model thought on the card it filled");
    run.onContent("an answer");
    full.add("card-1");
    run.onContent("an answer, and a little more");
    // The turn's thinking in full, as the endpoint reports it, on a run that has moved on.
    run.onReasoning("what the model thought on the card it filled, and then thought some more");

    final var thinking = lastStreamed();
    assertThat(thinking[0]).isEqualTo("card-2");
    assertThat(thinking[1]).isEqualTo(FeishuCardElements.REASONING_BODY);
    // Thinking is the longest thing on most cards. Written out again it would fill the card the
    // run has just moved onto with what the reader has above, and the card after that as well.
    assertThat(thinking[2]).doesNotContain("what the model thought on the card it filled");
    assertThat(thinking[2]).contains(", and then thought some more");
    assertThat(thinking[2]).contains(messages.get("card-continued").strip());
  }

  @Test
  @DisplayName("thinking that is all on the card above puts no pane on the card moved onto")
  void noPaneForThinkingThatIsAllOnTheCardAbove() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onReasoning("all of the thinking this turn did");
    run.onContent("an answer");
    full.add("card-1");
    run.onContent("an answer, and a little more");
    // The same thinking again — a model that has stopped thinking goes on reporting what it
    // thought. There is nothing new in it, so there is nothing for a pane here to hold.
    run.onReasoning("all of the thinking this turn did");

    assertThat(streamed)
        .noneMatch(
            write ->
                write[0].equals("card-2") && write[1].equals(FeishuCardElements.REASONING_BODY));
    assertThat(inserted)
        .noneMatch(
            insert ->
                insert[0].equals("card-2")
                    && insert[2].contains(FeishuCardElements.REASONING_BODY));
  }

  @Test
  @DisplayName("the pane folded away at the end holds that card's share of the thinking")
  void theFoldedPaneHoldsOnlyThisCardsThinking() {
    final var run =
        FeishuCardUpdater.forRun(card, new JsonMapper(), null, messages, elements(), null);

    run.onReasoning("what the model thought on the card it filled");
    run.onContent("an answer");
    full.add("card-1");
    run.onContent("an answer, and a little more");
    run.onReasoning("what the model thought on the card it filled, and then thought some more");
    run.onFinished(AgentOutcome.COMPLETED);

    // Folding the pane away rewrites the element whole, so it has to be cut the same way the
    // stream into it was: handed the turn's thinking entire, it puts all of it back on the card.
    final var folded =
        replaced.stream()
            .filter(change -> change[1].equals(FeishuCardElements.REASONING))
            .reduce((first, second) -> second)
            .orElseThrow();
    assertThat(folded[0]).isEqualTo("card-2");
    assertThat(folded[2]).doesNotContain("what the model thought on the card it filled");
    assertThat(folded[2]).contains(", and then thought some more");
  }

  private String[] lastStreamed() {
    assertThat(streamed).isNotEmpty();
    return streamed.get(streamed.size() - 1);
  }

  /** The real elements: what the card gains as the run first has something to put in them. */
  private FeishuCardElements elements() {
    return new FeishuCardElements(
        new JsonMapper(), messages, new ClassPathResource("feishu/card-elements.json"), null);
  }
}
