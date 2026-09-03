package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import me.kezhenxu94.springagent.integration.slack.config.SlackAutoConfiguration;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes the answers to questions the agent asked earlier and starts a fresh run to act on them.
 *
 * <p>There is no run to return to: the one that asked ended as soon as it had asked, possibly in a
 * different process. So the answers are carried in as a new user message on the same conversation,
 * which replays the chat memory holding the question and its tool call, and the agent picks up
 * where it left off.
 *
 * <p>Slack drops an interaction that is not acknowledged within three seconds. Closing the form is
 * fast enough to sit inside that, so it happens here; starting a run is not — it posts a message
 * before it returns — so that alone is handed to an executor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackQuestionAnswerHandler {

  private final PendingQuestionRepo pendingQuestionRepo;
  private final SlackQuestionForm questionForm;
  private final SlackQuestionFormCloser formCloser;
  private final SpringAgent springAgent;
  private final SlackMessages messages;
  private final JsonMapper om;

  /** Asked whether somebody who was not the person asked may answer anyway. */
  private final Admins admins;

  /**
   * Boot's general-purpose executor by name, since {@code taskScheduler} is a {@code TaskExecutor}
   * too. Deliberately not that one: its four threads exist to fire scheduled tasks on time, and
   * this work waits on Slack.
   */
  @Qualifier(SlackAutoConfiguration.TASK_EXECUTOR)
  private final TaskExecutor taskExecutor;

  public Response handle(final BlockActionRequest request, final ActionContext ctx)
      throws java.io.IOException {
    final var payload = request.getPayload();
    final var action = payload.getActions().isEmpty() ? null : payload.getActions().get(0);
    final var id = action == null ? null : action.getValue();
    if (Strings.isNullOrEmpty(id)) {
      return ctx.ack();
    }
    final var operator = payload.getUser() == null ? null : payload.getUser().getId();

    final var pending = pendingQuestionRepo.findById(id).orElse(null);
    if (pending == null) {
      log.warn("Answer for unknown pending question {}", id);
      return ephemeral(ctx, messages.get("question-already-answered"));
    }
    if (pending.status() != PendingQuestion.Status.PENDING) {
      // A form left standing after the row behind it closed — the update that should have taken it
      // away failed, or this press was already in flight. Say which of the two it is, since
      // "already answered" is not true of questions a later message overtook.
      return ephemeral(
          ctx,
          switch (pending.status()) {
            case SUPERSEDED -> messages.get("question-superseded");
            case EXPIRED -> messages.get("question-expired");
            default -> messages.get("question-already-answered");
          });
    }
    // Messages are shared, so in a channel everyone can see and press this form. Only the person
    // the agent was talking to gets to answer for them — or an administrator, who is trusted to
    // answer for anybody, and whose reason for existing is the channel where the agent is stuck on
    // a question the person it asked cannot answer and somebody else can.
    if (!Objects.equals(operator, pending.userId()) && !admins.isAdmin(operator)) {
      log.info("Answer for {} from {}, who is not {}", id, operator, pending.userId());
      return ephemeral(ctx, messages.get("question-not-yours"));
    }
    if (pending.expiresAt() != null && Instant.now().isAfter(pending.expiresAt())) {
      pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.EXPIRED);
      return ephemeral(ctx, messages.get("question-expired"));
    }

    final var questions = questionsOf(pending);
    final var answers = questionForm.answers(questions, id, state(payload));
    if (answers.isEmpty()) {
      // The form is deliberately left standing: they pressed submit having chosen nothing, and
      // taking the form away would leave them nothing to answer with.
      return ephemeral(ctx, messages.get("question-nothing-chosen"));
    }

    pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.ANSWERED);
    formCloser.answered(pending, summary(questions, answers));

    final var byAdmin = !Objects.equals(operator, pending.userId());
    taskExecutor.execute(() -> deliver(pending, questions, answers, byAdmin));
    return ephemeral(ctx, messages.get("question-submitted"));
  }

  /** Starts the run that acts on the answers. */
  private void deliver(
      final PendingQuestion pending,
      final List<Question> questions,
      final Map<String, String> answers,
      final boolean byAdmin) {
    try {
      final var heading =
          messages.get(byAdmin ? "question-answer-heading-by-admin" : "question-answer-heading");
      final var text = new StringBuilder(heading).append("\n");
      for (var i = 0; i < questions.size(); i++) {
        final var question = questions.get(i);
        final var answer = answers.get(SlackQuestionForm.headerOf(question, i));
        if (answer == null) {
          continue;
        }
        // The question is restated rather than referred to: some chat memory backends discard tool
        // messages entirely, so an answer on its own would be an answer to nothing.
        text.append("\n- ").append(question.question()).append("\n  ").append(answer);
      }
      final var message = text.toString();
      springAgent.fire(
          AgentRequest.builder()
              .requestId(pending.id())
              .scenario(BuiltInScenarios.CHAT)
              // The person who was asked, even when an administrator answered: an administrator
              // gets a say in that run, not ownership of it.
              .userId(pending.userId())
              .chatId(pending.chatId())
              .chatType(pending.chatType())
              .conversationId(pending.conversationId())
              .rootMessageId(pending.rootMessageId())
              .replyMessageId(pending.rootMessageId())
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

  /** What the form is replaced by once it has been answered. */
  private String summary(final List<Question> questions, final Map<String, String> answers) {
    final var out = new StringBuilder();
    for (var i = 0; i < questions.size(); i++) {
      final var answer = answers.get(SlackQuestionForm.headerOf(questions.get(i), i));
      if (answer == null) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append("\n");
      }
      out.append("> *").append(questions.get(i).question()).append("* — ").append(answer);
    }
    return out.toString();
  }

  /**
   * Slack's {@code state.values}, narrowed to what this reads.
   *
   * <p>Re-read through Jackson rather than walked through the SDK's own view-state types: those
   * model every element Slack can put in a view, and all this needs of them is a string per action.
   */
  private Map<String, Map<String, SlackQuestionForm.ViewValue>> state(
      final com.slack.api.app_backend.interactive_components.payload.BlockActionPayload payload) {
    if (payload.getState() == null || payload.getState().getValues() == null) {
      return Map.of();
    }
    final var out = new LinkedHashMap<String, Map<String, SlackQuestionForm.ViewValue>>();
    payload
        .getState()
        .getValues()
        .forEach(
            (blockId, actions) -> {
              final var values = new LinkedHashMap<String, SlackQuestionForm.ViewValue>();
              actions.forEach(
                  (actionId, value) -> {
                    final var selected =
                        value.getSelectedOption() == null
                            ? null
                            : new SlackQuestionForm.ViewValue.SelectedOption(
                                value.getSelectedOption().getValue());
                    values.put(
                        actionId, new SlackQuestionForm.ViewValue(value.getValue(), selected));
                  });
              out.put(blockId, values);
            });
    return out;
  }

  /**
   * Answered where only the presser can see it. The reply itself belongs to the conversation, and a
   * note about a button being pressed does not.
   */
  private Response ephemeral(final ActionContext ctx, final String text)
      throws java.io.IOException {
    ctx.respond(r -> r.responseType("ephemeral").replaceOriginal(false).text(text));
    return ctx.ack();
  }
}
