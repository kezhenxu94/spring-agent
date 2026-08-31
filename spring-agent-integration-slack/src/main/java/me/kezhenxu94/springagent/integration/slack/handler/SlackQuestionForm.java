package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.model.block.InputBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.OptionObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.PlainTextInputElement;
import com.slack.api.model.block.element.RadioButtonsElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.stereotype.Component;

/**
 * The agent's questions as something a person can answer in the message itself.
 *
 * <p><b>Not a modal.</b> {@code views.open} needs a {@code trigger_id}, which only exists in
 * response to something the user just pressed — and the agent asks unprompted, in the middle of its
 * own turn. So the form is {@code input} blocks in the reply plus a submit button, and the {@code
 * block_actions} payload the button produces carries every input under {@code state.values}.
 *
 * <p><b>The block ids are derived from the pending question's id rather than fixed.</b> A block id
 * has to be unique across a message and a run may ask more than once, so fixed names make the
 * second ask fail outright. They are also the keys the answers are read back out of, which is the
 * reason this class owns both halves: nothing at runtime would report a mismatch between the names
 * written onto the form and the names read off the press — the answer would simply be empty, and
 * the run would resume having learned nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackQuestionForm {

  /** What the submit button carries, distinguishing it from the reply's stop button. */
  public static final String ACTION_ID = "sa_answer";

  private final SlackMessages messages;

  /** The block holding question {@code index} of the ask identified by {@code pendingId}. */
  static String questionBlockId(final String pendingId, final int index) {
    return "sa_q_" + pendingId + "_" + index;
  }

  /** The free-text block for that same question. */
  static String otherBlockId(final String pendingId, final int index) {
    return "sa_x_" + pendingId + "_" + index;
  }

  static String selectActionId(final int index) {
    return "sa_select_" + index;
  }

  static String otherActionId(final int index) {
    return "sa_other_" + index;
  }

  /** The form, as blocks to append to the reply. */
  public List<LayoutBlock> blocks(final List<Question> questions, final String pendingId) {
    final var blocks = new ArrayList<LayoutBlock>();
    blocks.add(SlackBlockKit.divider());
    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      blocks.add(SlackBlockKit.markdown("*" + Strings.nullToEmpty(question.question()) + "*"));

      final var options = question.options();
      if (options != null && !options.isEmpty()) {
        final var choices = new ArrayList<OptionObject>();
        for (final var option : options) {
          final var choice =
              OptionObject.builder()
                  .text(
                      PlainTextObject.builder()
                          .text(SlackBlockKit.clamp(option.label(), 75))
                          .build())
                  // The label, not the index: it is what the model asked about, and it survives the
                  // run that asked ending — the press may arrive in another process entirely.
                  .value(SlackBlockKit.clamp(option.label(), 150));
          // Left off entirely rather than set to null: Slack's own model validates this field and
          // refuses a null outright, so a model that described none of its options would otherwise
          // fail the whole ask rather than produce a plainer form.
          if (!Strings.isNullOrEmpty(option.description())) {
            choice.description(
                PlainTextObject.builder()
                    .text(SlackBlockKit.clamp(option.description(), 75))
                    .build());
          }
          choices.add(choice.build());
        }
        blocks.add(
            InputBlock.builder()
                .blockId(questionBlockId(pendingId, i))
                // Optional, because the free-text box below is the other way to answer and a form
                // that insists on both cannot be submitted at all.
                .optional(true)
                .label(
                    PlainTextObject.builder()
                        .text(SlackBlockKit.clamp(messages.get("question-select-hint"), 75))
                        .build())
                .element(
                    RadioButtonsElement.builder()
                        .actionId(selectActionId(i))
                        .options(choices)
                        .build())
                .build());
      }

      blocks.add(
          InputBlock.builder()
              .blockId(otherBlockId(pendingId, i))
              .optional(true)
              .label(
                  PlainTextObject.builder()
                      .text(SlackBlockKit.clamp(messages.get("question-other-hint"), 75))
                      .build())
              .element(PlainTextInputElement.builder().actionId(otherActionId(i)).build())
              .build());
    }

    final List<BlockElement> submit =
        List.of(
            SlackBlockKit.button(ACTION_ID, messages.get("question-submit"), pendingId, "primary"));
    blocks.add(SlackBlockKit.actions("sa_submit_" + pendingId, submit));
    return blocks;
  }

  /**
   * The answers a press carried, keyed by each question's header the way the tool expects them.
   *
   * <p>Typed text beats a selection where both are present: somebody who wrote something meant it,
   * and a radio button they also happened to leave selected is the weaker signal.
   */
  public Map<String, String> answers(
      final List<Question> questions,
      final String pendingId,
      final Map<String, Map<String, ViewValue>> state) {
    final var answers = new LinkedHashMap<String, String>();
    if (state == null) {
      return answers;
    }
    for (var i = 0; i < questions.size(); i++) {
      final var question = questions.get(i);
      final var typed = value(state.get(otherBlockId(pendingId, i)), otherActionId(i));
      final var selected = value(state.get(questionBlockId(pendingId, i)), selectActionId(i));
      final var answer = !Strings.isNullOrEmpty(typed) ? typed : selected;
      if (!Strings.isNullOrEmpty(answer)) {
        answers.put(headerOf(question, i), answer);
      }
    }
    return answers;
  }

  /**
   * What the tool keys an answer by. The header where the model gave one; otherwise its position,
   * which is at least stable between writing the form and reading it.
   */
  static String headerOf(final Question question, final int index) {
    return Strings.isNullOrEmpty(question.header())
        ? "question" + (index + 1)
        : question.header().toLowerCase(Locale.ROOT);
  }

  private static String value(final Map<String, ViewValue> block, final String actionId) {
    if (block == null) {
      return null;
    }
    final var value = block.get(actionId);
    if (value == null) {
      return null;
    }
    if (!Strings.isNullOrEmpty(value.value())) {
      return value.value();
    }
    return value.selectedOption() == null ? null : value.selectedOption().value();
  }

  /** A form that is on a message and waiting to be answered. */
  public record Pending(String pendingId, List<Question> questions) {}

  /**
   * The part of Slack's {@code state.values} this reads.
   *
   * <p>Declared here rather than taken from the SDK's own view-state type so that the parsing can
   * be tested without building a whole {@code BlockActionPayload}, which is what makes the
   * write-then-read round trip assertable at all.
   */
  public record ViewValue(String value, SelectedOption selectedOption) {
    public record SelectedOption(String value) {}
  }
}
