package me.kezhenxu94.springagent.integration.feishu.handler;

import static me.kezhenxu94.springagent.integration.feishu.handler.FeishuToasts.toast;

import com.google.common.base.Strings;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Takes the answers to questions the agent asked earlier and starts a fresh run to act on them.
 *
 * <p>There is no run to return to: the one that asked ended as soon as it had asked, possibly in a
 * different process. So the answers are carried in as a new user message on the same conversation,
 * which replays the chat memory holding the question and its tool call, and the agent picks up
 * where it left off.
 *
 * <p>Feishu drops a callback that takes more than three seconds, and only the checks below are fast
 * enough to sit inside that. Starting the run is not — it creates and replies a card before
 * returning — so it is handed to an executor, along with rewriting the card, which the card service
 * would refuse anyway while this callback is still in flight.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuQuestionAnswerHandler {

  /** The value the submit button carries, distinguishing it from the reply card's stop button. */
  public static final String ACTION = "answer";

  private static final String VALUE_PENDING_QUESTION_ID = "pq";

  private final PendingQuestionRepo pendingQuestionRepo;
  private final FeishuQuestionForm questionForm;
  private final FeishuQuestionFormCloser formCloser;
  private final SpringAgent springAgent;
  private final FeishuMessages messages;

  /**
   * Boot's general-purpose executor by name, since {@code taskScheduler} is a {@code TaskExecutor}
   * too. Deliberately not that one: its four threads exist to fire scheduled tasks on time, and
   * this work waits on Feishu.
   */
  @Qualifier("applicationTaskExecutor")
  private final TaskExecutor taskExecutor;

  public P2CardActionTriggerResponse handle(final P2CardActionTrigger event) {
    final var action = event.getEvent().getAction();
    final var id = String.valueOf(action.getValue().get(VALUE_PENDING_QUESTION_ID));

    final var pending = pendingQuestionRepo.findById(id).orElse(null);
    if (pending == null) {
      log.warn("Answer for unknown pending question {}", id);
      return toast("warning", messages.get("question-already-answered"));
    }
    if (pending.status() != PendingQuestion.Status.PENDING) {
      log.info("Answer for {} which is already {}", id, pending.status());
      // A form left standing after the row behind it closed — the card update that should have
      // taken it away failed, or this press was already in flight. Say which of the two it is,
      // since "already answered" is not true of questions a later message overtook.
      return switch (pending.status()) {
        case SUPERSEDED -> toast("info", messages.get("question-superseded"));
        case EXPIRED -> toast("warning", messages.get("question-expired"));
        default -> toast("info", messages.get("question-already-answered"));
      };
    }
    // Cards are shared, so in a group chat everyone can see and press this form. Only the person
    // the agent was talking to gets to answer for them.
    final var operator = event.getEvent().getOperator().getOpenId();
    if (!Objects.equals(operator, pending.userId())) {
      log.info("Answer for {} from {}, who is not {}", id, operator, pending.userId());
      return toast("warning", messages.get("question-not-yours"));
    }
    if (pending.expiresAt() != null && Instant.now().isAfter(pending.expiresAt())) {
      pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.EXPIRED);
      return toast("warning", messages.get("question-expired"));
    }

    final var questions = questions(pending);
    final var answers = questionForm.answers(questions, action.getFormValue(), id);
    if (answers.isEmpty()) {
      // Nothing was chosen, so there is nothing to tell the agent. The form is deliberately left
      // as it is, so they can answer properly rather than having to start over.
      return toast("warning", messages.get("question-nothing-chosen"));
    }

    pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.ANSWERED);
    final var replyTo = event.getEvent().getContext().getOpenMessageId();
    taskExecutor.execute(() -> deliver(pending, questions, answers, replyTo));
    return toast("success", messages.get("question-submitted"));
  }

  /** The slow half, off the callback's three-second budget. */
  private void deliver(
      final PendingQuestion pending,
      final List<Question> questions,
      final Map<String, String> answers,
      final String replyTo) {
    try {
      formCloser.answered(pending, answers);
    } catch (Exception e) {
      // The agent has the answers either way; a form left on screen is untidy, not broken, and the
      // row is already ANSWERED so pressing it again is refused.
      log.warn("Failed to close the question form on card {}", pending.cardId(), e);
    }
    try {
      springAgent.fire(
          AgentRequest.builder()
              .requestId(pending.id())
              .scenario(AgentScenario.CHAT)
              .userId(pending.userId())
              .chatId(pending.chatId())
              .chatType(pending.chatType())
              .conversationId(pending.conversationId())
              .rootMessageId(pending.rootMessageId())
              .replyMessageId(replyTo)
              .userMessage(user -> user.text(message(questions, answers)))
              .build());
    } catch (Exception e) {
      log.error("Failed to resume conversation {} with answers", pending.conversationId(), e);
    }
  }

  /**
   * What the answers are said to the agent as.
   *
   * <p>Restates the questions rather than trusting memory to still hold them. The window replayed
   * to the model is bounded, so a busy conversation may have dropped the asking, and the JDBC chat
   * memory repository discards tool-call messages entirely — under either, "Postgres" on its own
   * would be an answer to nothing.
   */
  private String message(final List<Question> questions, final Map<String, String> answers) {
    final var message = new StringBuilder(messages.get("question-answer-heading"));
    for (final var question : questions) {
      final var answer = answers.get(question.question());
      if (Strings.isNullOrEmpty(answer)) {
        continue;
      }
      message.append("\n\n").append(question.question()).append("\n→ ").append(answer);
    }
    return message.toString();
  }

  private List<Question> questions(final PendingQuestion pending) {
    return questionForm.questions(pending.questionsJson());
  }
}
