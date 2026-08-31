package me.kezhenxu94.springagent.integration.slack.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts the agent's questions to the user and returns immediately, without an answer.
 *
 * <p>This is the whole of the design, and it is not what the tool's own documentation suggests. The
 * obvious implementation blocks the calling thread on a future until the user replies; that thread
 * belongs to the agent's run, so blocking it holds a reply open, keeps a slot out of the pool,
 * makes shutdown wait, and loses the question outright if the process restarts — for a person who
 * may take an hour, or forget entirely.
 *
 * <p>So nothing waits. The questions go onto the reply, a row records everything needed to pick the
 * conversation back up, and the run ends normally. Whenever the answer arrives, {@link
 * SlackQuestionAnswerHandler} starts a fresh run on the same conversation, carrying the questions
 * and the answers in as a new user message. An answer after a restart is no different from an
 * answer after five seconds.
 *
 * <p>Per run rather than a bean: the tool's handler interface is handed nothing but the questions,
 * so which conversation they belong to has to be captured when the handler is built.
 *
 * <p>No {@code SynchronousQuestionHandler} here, deliberately — that marker says the turn may carry
 * on because an answer is available inside the call, and on Slack it never is.
 */
@Slf4j
@RequiredArgsConstructor
public class SlackQuestionHandler implements QuestionHandler {

  private final AgentRequest request;
  private final SlackMessageUpdater updater;
  private final SlackMessage message;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final JsonMapper om;
  private final Duration ttl;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    final var id = UUID.randomUUID().toString().replace("-", "");
    final var now = Instant.now();

    // Written before the form goes up, so a press can never arrive for a row that does not exist.
    pendingQuestionRepo.save(
        PendingQuestion.builder()
            .id(id)
            .userId(request.userId())
            .chatId(request.chatId())
            .chatType(request.chatType())
            .conversationId(request.conversationId())
            .rootMessageId(request.rootMessageId())
            // The field is named for a card and is opaque to core, which never reads it: here it
            // holds the timestamp of the message the form is on, which is what chat.update needs to
            // take the form away again.
            .cardId(message.ts())
            .questionsJson(om.writeValueAsString(questions))
            .status(PendingQuestion.Status.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(ttl))
            .build());

    // Appended to whatever the run has already said rather than replacing it: the question follows
    // from the answer above it, and a reader needs both.
    updater.showQuestionForm(id, questions);
    log.info(
        "Asked {} question(s): pendingQuestionId={}, conversationId={}, ts={}",
        questions.size(),
        id,
        request.conversationId(),
        message.ts());

    // Nothing yet: the caller turns an empty answer into the note that ends the model's turn.
    return Map.of();
  }
}
