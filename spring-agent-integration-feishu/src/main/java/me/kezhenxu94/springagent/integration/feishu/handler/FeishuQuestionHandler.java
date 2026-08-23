package me.kezhenxu94.springagent.integration.feishu.handler;

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
 * belongs to the agent's run, so blocking it holds a card open, keeps a slot out of the pool, makes
 * shutdown wait, and loses the question outright if the process restarts — for a person who may
 * take an hour, or forget entirely.
 *
 * <p>So nothing waits. The questions go onto the card, a row records everything needed to pick the
 * conversation back up, and the run ends normally. Whenever the answer arrives, {@link
 * FeishuQuestionAnswerHandler} starts a fresh run on the same conversation, carrying the questions
 * and the answers in as a new user message. An answer after a restart is no different from an
 * answer after five seconds.
 *
 * <p>Per run rather than a bean: the tool's handler interface is handed nothing but the questions,
 * so which conversation they belong to has to be captured when the handler is built.
 */
@Slf4j
@RequiredArgsConstructor
public class FeishuQuestionHandler implements QuestionHandler {

  private final AgentRequest request;
  private final FeishuCard card;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final FeishuQuestionForm questionForm;
  private final JsonMapper om;
  private final Duration ttl;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    final var id = UUID.randomUUID().toString();
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
            .cardId(card.cardId())
            .questionsJson(om.writeValueAsString(questions))
            .status(PendingQuestion.Status.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(ttl))
            .build());

    final var posted = questionForm.insert(card, questions, id);
    if (!posted) {
      // Nothing to answer means nothing will ever answer it; leaving the row PENDING would only
      // block the next typed reply from being taken as the answer to something.
      pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.EXPIRED);
      // Thrown: the caller counts the channels that managed to ask, and this one did not.
      throw new IllegalStateException(
          "Could not insert the question form into card "
              + card.cardId()
              + " for user "
              + request.userId());
    }

    log.info(
        "Asked {} question(s): pendingQuestionId={}, conversationId={}, cardId={}",
        questions.size(),
        id,
        request.conversationId(),
        card.cardId());
    // Nothing yet: the caller turns an empty answer into the note that ends the model's turn.
    return Map.of();
  }
}
