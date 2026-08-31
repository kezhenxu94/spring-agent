package me.kezhenxu94.springagent.integration.slack.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.LayoutBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;

/**
 * That what the form writes is what the press reads back.
 *
 * <p>This is the failure nothing at runtime reports: the block ids written onto the form are the
 * keys the answers are looked up under, and a mismatch does not throw — it produces an empty answer
 * map, and the run resumes having learned nothing from a person who did answer.
 */
class SlackQuestionFormTest {

  private static final String PENDING = "abc123";

  private final SlackQuestionForm form = new SlackQuestionForm(new SlackMessages(properties()));

  private static SlackProperties properties() {
    return new SlackProperties("xoxb", "xapp", "U0BOT", "T0TEAM", Locale.ENGLISH, null, null);
  }

  private static Question question(final String header, final String... options) {
    return new Question(
        "Which one?",
        header,
        java.util.Arrays.stream(options)
            // A description is not optional on the tool's own Option, which validates it.
            .map(label -> new Question.Option(label, "why you might pick " + label))
            .toList(),
        false);
  }

  @Test
  @DisplayName("a selection written onto the form is read back under the question's header")
  void shouldRoundTripASelection() {
    final var questions = List.of(question("backend", "Postgres", "SQLite"));

    final var blocks = form.blocks(questions, PENDING);
    final var state = selected(blocks, PENDING, 0, "Postgres");

    assertThat(form.answers(questions, PENDING, state)).containsEntry("backend", "Postgres");
  }

  @Test
  @DisplayName("typed text beats a selection, because somebody who typed meant it")
  void shouldPreferTypedText() {
    final var questions = List.of(question("backend", "Postgres"));
    final var state = selected(form.blocks(questions, PENDING), PENDING, 0, "Postgres");
    state
        .get(SlackQuestionForm.otherBlockId(PENDING, 0))
        .put(
            SlackQuestionForm.otherActionId(0),
            new SlackQuestionForm.ViewValue("MySQL, actually", null));

    assertThat(form.answers(questions, PENDING, state)).containsEntry("backend", "MySQL, actually");
  }

  @Test
  @DisplayName("a form answered with nothing yields nothing, rather than a blank answer")
  void shouldYieldNothingWhenNothingWasChosen() {
    final var questions = List.of(question("backend", "Postgres"));

    assertThat(form.answers(questions, PENDING, Map.of())).isEmpty();
  }

  @Test
  @DisplayName("two asks on one message use different block ids, which Slack requires")
  void shouldNameEachAskDifferently() {
    final var questions = List.of(question("backend", "Postgres"));

    final var first = blockIds(form.blocks(questions, "aaa"));
    final var second = blockIds(form.blocks(questions, "bbb"));

    assertThat(first).doesNotContainAnyElementsOf(second);
  }

  @Test
  @DisplayName("the submit button carries the pending id, which is how the press is matched to it")
  void shouldCarryThePendingId() {
    final var blocks = form.blocks(List.of(question("backend", "Postgres")), PENDING);

    final var actions =
        blocks.stream()
            .filter(ActionsBlock.class::isInstance)
            .map(ActionsBlock.class::cast)
            .toList();

    assertThat(actions).hasSize(1);
    assertThat(actions.getFirst().getElements()).hasSize(1);
    assertThat(
            ((com.slack.api.model.block.element.ButtonElement)
                    actions.getFirst().getElements().getFirst())
                .getValue())
        .isEqualTo(PENDING);
  }

  @Test
  @DisplayName("a question with no options still gets a way to answer it")
  void shouldStillOfferFreeText() {
    final var open = new Question("Say more?", "detail", List.of(), false);

    final var ids = blockIds(form.blocks(List.of(open), PENDING));

    assertThat(ids).contains(SlackQuestionForm.otherBlockId(PENDING, 0));
  }

  @Test
  @DisplayName("a header is keyed the same way whichever case the model wrote it in")
  void shouldKeyHeadersConsistently() {
    final var shouty = question("Backend", "Postgres");
    final var state = selected(form.blocks(List.of(shouty), PENDING), PENDING, 0, "Postgres");

    // Lowercased on the way out and on the way in. If the two ever disagree the answer is simply
    // absent — no exception, no log, and a run that resumes having learned nothing.
    assertThat(form.answers(List.of(shouty), PENDING, state)).containsOnlyKeys("backend");
  }

  private static List<String> blockIds(final List<LayoutBlock> blocks) {
    return blocks.stream()
        .map(
            block ->
                block instanceof InputBlock input
                    ? input.getBlockId()
                    : block instanceof ActionsBlock actions ? actions.getBlockId() : null)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /** The state a press would carry, built from the very blocks the form just produced. */
  private static Map<String, Map<String, SlackQuestionForm.ViewValue>> selected(
      final List<LayoutBlock> blocks, final String pendingId, final int index, final String value) {
    assertThat(blockIds(blocks)).contains(SlackQuestionForm.questionBlockId(pendingId, index));
    final var state = new LinkedHashMap<String, Map<String, SlackQuestionForm.ViewValue>>();
    state.put(
        SlackQuestionForm.questionBlockId(pendingId, index),
        new LinkedHashMap<>(
            Map.of(
                SlackQuestionForm.selectActionId(index),
                new SlackQuestionForm.ViewValue(
                    null, new SlackQuestionForm.ViewValue.SelectedOption(value)))));
    state.put(SlackQuestionForm.otherBlockId(pendingId, index), new LinkedHashMap<>());
    return state;
  }
}
