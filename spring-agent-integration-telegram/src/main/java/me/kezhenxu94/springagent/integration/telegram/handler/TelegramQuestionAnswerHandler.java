package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.logging.RunMdc;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.CallbackQuery;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAutoConfiguration;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes a press on a question form and, once it is an answer, starts the run that acts on it —
 * {@code SlackQuestionAnswerHandler}'s counterpart, with the same checks in the same order: the row
 * must still be open, the presser must be the person asked or an administrator, and the question
 * must not have expired.
 *
 * <p>A press is an answer only where one press is the whole of one ({@link
 * TelegramQuestionForm#answeredByOnePress}); anywhere else it marks the option on the keyboard and
 * Submit sends what is marked. Marking is a keyboard edit and nothing more, so it changes no row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramQuestionAnswerHandler {

  private final TelegramBotApi telegram;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final TelegramQuestionForm questionForm;
  private final TelegramQuestionFormCloser formCloser;
  private final SpringAgent springAgent;
  private final TelegramMessages messages;
  private final JsonMapper om;
  private final Admins admins;

  @Qualifier(TelegramAutoConfiguration.TASK_EXECUTOR)
  private final TaskExecutor taskExecutor;

  public void handle(final CallbackQuery query) {
    final var data = Strings.nullToEmpty(query.data());
    final var press = TelegramQuestionForm.press(data).orElse(null);
    final var pendingId =
        press != null
            ? press.pendingId()
            : data.startsWith(TelegramKeyboards.SUBMIT)
                ? data.substring(TelegramKeyboards.SUBMIT.length())
                : null;
    if (Strings.isNullOrEmpty(pendingId)) {
      notice(query, null);
      return;
    }
    final var operator = query.from() == null ? null : String.valueOf(query.from().id());

    final var pending = pendingQuestionRepo.findById(pendingId).orElse(null);
    if (pending == null) {
      notice(query, messages.get("question-already-answered"));
      return;
    }
    if (pending.status() != PendingQuestion.Status.PENDING) {
      notice(
          query,
          switch (pending.status()) {
            case SUPERSEDED -> messages.get("question-superseded");
            case EXPIRED -> messages.get("question-expired");
            default -> messages.get("question-already-answered");
          });
      return;
    }
    // A group can see and press the form; only the person asked — or an administrator — answers.
    if (!Objects.equals(operator, pending.userId()) && !admins.isAdmin(operator)) {
      notice(query, messages.get("question-not-yours"));
      return;
    }
    if (pending.expiresAt() != null && Instant.now().isAfter(pending.expiresAt())) {
      pendingQuestionRepo.updateStatus(pendingId, PendingQuestion.Status.EXPIRED);
      notice(query, messages.get("question-expired"));
      return;
    }

    final var questions = questionsOf(pending);
    final Map<String, String> answers;
    if (press != null) {
      if (!TelegramQuestionForm.offered(questions, press)) {
        notice(query, null);
        return;
      }
      if (!TelegramQuestionForm.answeredByOnePress(questions)) {
        mark(query, questions, press);
        return;
      }
      answers = questionForm.answer(questions, press);
    } else {
      answers =
          questionForm.answers(
              questions, pendingId, query.message() == null ? null : query.message().replyMarkup());
    }
    if (answers.isEmpty()) {
      notice(query, messages.get("question-nothing-chosen"));
      return;
    }

    pendingQuestionRepo.updateStatus(pendingId, PendingQuestion.Status.ANSWERED);
    formCloser.answered(pending, query.message(), summary(questions, answers));
    notice(query, messages.get("question-submitted"));

    final var byAdmin = !Objects.equals(operator, pending.userId());
    taskExecutor.execute(
        () -> {
          try (var ignored = RunMdc.of(pending.id(), pending.conversationId(), pending.userId())) {
            deliver(pending, questions, answers, byAdmin);
          }
        });
  }

  /** Marks the pressed option on the keyboard, which is where a form's choices are kept. */
  private void mark(
      final CallbackQuery query,
      final List<Question> questions,
      final TelegramQuestionForm.Press press) {
    final var shown = query.message();
    if (shown == null || shown.replyMarkup() == null) {
      notice(query, null);
      return;
    }
    try {
      telegram.editMessageReplyMarkup(
          String.valueOf(shown.chat().id()),
          shown.messageId(),
          questionForm.toggle(shown.replyMarkup(), questions, press));
    } catch (Exception e) {
      log.warn("Could not mark an option on {}: {}", press.pendingId(), e.getMessage());
    }
    notice(query, null);
  }

  /** Starts the run that acts on the answers — see the Slack handler for why it restates them. */
  private void deliver(
      final PendingQuestion pending,
      final List<Question> questions,
      final Map<String, String> answers,
      final boolean byAdmin) {
    try {
      final var text =
          new StringBuilder(
                  messages.get(
                      byAdmin ? "question-answer-heading-by-admin" : "question-answer-heading"))
              .append("\n");
      for (var i = 0; i < questions.size(); i++) {
        final var answer = answers.get(TelegramQuestionForm.headerOf(questions.get(i), i));
        if (answer != null) {
          text.append("\n- ").append(questions.get(i).question()).append("\n  ").append(answer);
        }
      }
      final var message = text.toString();
      springAgent.fire(
          AgentRequest.builder()
              .requestId(pending.id())
              .scenario(BuiltInScenarios.CHAT)
              .userId(pending.userId())
              .chatId(pending.chatId())
              .chatType(pending.chatType())
              .groupId(pending.groupId())
              .tenantId(pending.tenantId())
              .conversationId(pending.conversationId())
              .rootMessageId(pending.rootMessageId())
              // Under the form, which is where whoever answered is looking.
              .replyMessageId(
                  TelegramMessageId.messageIdOrNull(pending.cardId()) == null
                      ? pending.rootMessageId()
                      : pending.cardId())
              .userMessage(user -> user.text(message))
              .build());
    } catch (Exception e) {
      log.error("Could not start a run for the answers to {}", pending.id(), e);
    }
  }

  private List<Question> questionsOf(final PendingQuestion pending) {
    try {
      return om.readValue(pending.questionsJson(), new TypeReference<List<Question>>() {});
    } catch (Exception e) {
      log.warn("Could not read the questions behind {}", pending.id(), e);
      return List.of();
    }
  }

  /** What is added under the form once it has been answered. */
  private static TelegramText summary(
      final List<Question> questions, final Map<String, String> answers) {
    final var out = TelegramText.builder();
    for (var i = 0; i < questions.size(); i++) {
      final var answer = answers.get(TelegramQuestionForm.headerOf(questions.get(i), i));
      if (answer == null) {
        continue;
      }
      if (!out.isEmpty()) {
        out.text("\n");
      }
      out.text("✅ ").styled("bold", questions.get(i).question()).text(" — " + answer);
    }
    return out.build();
  }

  /** Answers the press, with a notice only the presser sees where there is something to say. */
  private void notice(final CallbackQuery query, final String text) {
    try {
      telegram.answerCallbackQuery(query.id(), text);
    } catch (Exception e) {
      log.debug("Could not answer a button press: {}", e.getMessage());
    }
  }
}
