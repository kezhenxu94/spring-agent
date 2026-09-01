package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.SynchronousQuestionHandler;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.websocket.run.RunEvent;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournal;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts the agent's questions in front of the browser and does not wait for the answers.
 *
 * <p>Deliberately <b>not</b> a {@link SynchronousQuestionHandler}, which the command line is and
 * which would keep the question, the answer and the work that follows it in one turn. That is the
 * right shape at a keyboard, where the user is watching and the session dies with the terminal. It
 * is the wrong shape here, and getting it wrong is what would break the requirement this whole
 * module is built around: a synchronous handler holds a run open waiting for a person, so closing
 * the tab would either strand a thread until it timed out or lose the question entirely.
 *
 * <p>Asynchronous instead, exactly as Feishu does it. The questions are written down, the run ends,
 * and the answer arrives later as a <em>new</em> request on the same conversation. The consequence
 * is the good one: an unanswered question lives in the database, so it survives a refresh, a closed
 * tab, and a restart of the server — none of which the in-memory journal survives.
 */
@Slf4j
@RequiredArgsConstructor
public class WebQuestionHandler implements QuestionHandler {

  private final AgentRequest request;
  private final RunJournal journal;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final JsonMapper om;
  private final Duration ttl;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    if (questions == null || questions.isEmpty()) {
      return Map.of();
    }
    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();

    // Written before it is shown. If the write fails there is nothing to answer against, and a form
    // on screen that no answer can be matched to is worse than no form.
    pendingQuestionRepo.save(
        PendingQuestion.builder()
            .id(id)
            .userId(request.userId())
            .chatId(request.chatId())
            .chatType(request.chatType())
            .conversationId(request.conversationId())
            .rootMessageId(request.rootMessageId())
            .questionsJson(om.writeValueAsString(questions))
            .status(PendingQuestion.Status.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(ttl))
            .build());

    journal.append(
        RunEvent.of(
            RunEvent.QUESTION,
            Map.of("pendingQuestionId", id, "questions", WebQuestions.asJson(questions))));

    log.info(
        "Asked {} question(s) as {} in conversation {}",
        questions.size(),
        id,
        request.conversationId());

    // Empty, which is what the caller turns into the note that ends the model's turn. Anything else
    // would be a made-up answer.
    return Map.of();
  }
}
