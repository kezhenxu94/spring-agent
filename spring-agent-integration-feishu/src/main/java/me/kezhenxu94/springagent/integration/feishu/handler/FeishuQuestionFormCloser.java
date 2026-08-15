package me.kezhenxu94.springagent.integration.feishu.handler;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReqBody;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import org.springframework.stereotype.Component;

/**
 * Takes a form off the card once its questions have stopped being answerable, leaving a plain
 * record of what was asked in its place.
 *
 * <p>A form outlives the run that put it there, and there are two ways it ends: the user answers
 * it, or they say what they wanted in a message instead. Both close the row, so both have to close
 * the card as well — a form still offering controls after the row behind it is closed can only be
 * pressed to be refused.
 *
 * <p>Shared rather than sitting in either handler because the run's {@link FeishuCardUpdater}, and
 * the sequence counter that makes card writes safe, are gone by the time any of this happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuQuestionFormCloser {

  private final Client feishu;
  private final FeishuQuestionForm questionForm;
  private final FeishuCardSequences sequences;

  /** Leaves what was asked and what was chosen. */
  public void answered(final PendingQuestion pending, final Map<String, String> answers) {
    final var questions = questionForm.questions(pending.questionsJson());
    replace(pending, questionForm.answered(questions, answers, pending.id()), "answered");
  }

  /** Leaves what was asked, and says that a later message overtook it. */
  public void superseded(final PendingQuestion pending) {
    final var questions = questionForm.questions(pending.questionsJson());
    replace(pending, questionForm.superseded(questions, pending.id()), "superseded");
  }

  /**
   * The sequence comes from {@link FeishuCardSequences}, shared with the run's {@link
   * FeishuCardUpdater}, because the run this is closing a form on may still be streaming: a user
   * can answer before the model has finished saying it will wait. Counting from the clock here
   * instead left every write that run had still to make below what the card had seen, and so
   * refused.
   *
   * @param reason also distinguishes the two closings as idempotency keys, so a form closed one way
   *     is not mistaken for a retry of the other and silently dropped
   */
  private void replace(final PendingQuestion pending, final String element, final String reason) {
    if (!write(pending, element, reason, sequences.next(pending.cardId()))) {
      // The run that put the form up may be streaming on another replica, whose counter this one
      // cannot see. The card says only that this was too low, never what it has, so catch up on
      // the clock and try once more.
      write(pending, element, reason, sequences.resync(pending.cardId()));
    }
  }

  /** Writes the element, answering whether the card took it. */
  @SneakyThrows
  private boolean write(
      final PendingQuestion pending,
      final String element,
      final String reason,
      final int sequence) {
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .update(
                UpdateCardElementReq.newBuilder()
                    .cardId(pending.cardId())
                    .elementId(FeishuQuestionForm.formElementId(pending.id()))
                    .updateCardElementReqBody(
                        UpdateCardElementReqBody.newBuilder()
                            .uuid(pending.id() + "-" + reason)
                            .sequence(sequence)
                            .element(element)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to close the question form as {}: cardId={}, seq={}, code={}, msg={}",
          reason,
          pending.cardId(),
          sequence,
          response.getCode(),
          response.getMsg());
      return false;
    }
    return true;
  }
}
