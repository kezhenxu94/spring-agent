package me.kezhenxu94.springagent.integration.feishu.handler;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReqBody;
import java.time.Instant;
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
 * <p>Shared rather than sitting in either handler because the run's {@link FeishuCard}, and the
 * sequence counter that makes card writes safe, are gone by the time any of this happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuQuestionFormCloser {

  private final Client feishu;
  private final FeishuQuestionForm questionForm;

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
   * The sequence is taken from the clock rather than continued from the run's counter, which ended
   * with the run and cannot be recovered. The card only requires that each operation's sequence be
   * higher than the last, and seconds since the epoch is far past any count a run could have
   * reached while still fitting the field.
   *
   * @param reason also distinguishes the two closings as idempotency keys, so a form closed one way
   *     is not mistaken for a retry of the other and silently dropped
   */
  @SneakyThrows
  private void replace(final PendingQuestion pending, final String element, final String reason) {
    final var elementId = FeishuQuestionForm.formElementId(pending.id());
    final var uuid = pending.id() + "-" + reason;
    final var sequence = (int) Instant.now().getEpochSecond();
    log.info(
        "Closing question form: pendingQuestionId={}, cardId={}, elementId={}, uuid={}, "
            + "sequence={}, reason={}, element={}",
        pending.id(),
        pending.cardId(),
        elementId,
        uuid,
        sequence,
        reason,
        element);
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .update(
                UpdateCardElementReq.newBuilder()
                    .cardId(pending.cardId())
                    .elementId(elementId)
                    .updateCardElementReqBody(
                        UpdateCardElementReqBody.newBuilder()
                            .uuid(uuid)
                            .sequence(sequence)
                            .element(element)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to close the question form as {}: pendingQuestionId={}, cardId={}, "
              + "elementId={}, uuid={}, sequence={}, code={}, msg={}",
          reason,
          pending.id(),
          pending.cardId(),
          elementId,
          uuid,
          sequence,
          response.getCode(),
          response.getMsg());
    } else {
      log.info(
          "Closed question form as {}: pendingQuestionId={}, cardId={}, elementId={}, uuid={}, "
              + "sequence={}, requestId={}",
          reason,
          pending.id(),
          pending.cardId(),
          elementId,
          uuid,
          sequence,
          response.getRequestId());
    }
  }
}
