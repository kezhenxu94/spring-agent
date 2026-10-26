package me.kezhenxu94.springagent.integration.telegram.handler;

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
 * Puts the agent's questions to the user and returns at once, without an answer — {@code
 * SlackQuestionHandler}'s design, for the reasons given there: nothing waits, a row records how to
 * pick the conversation up, and the answer starts a fresh run whenever it arrives.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramQuestionHandler implements QuestionHandler {

  private final AgentRequest request;
  private final TelegramMessageUpdater updater;
  private final TelegramMessage message;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final JsonMapper om;
  private final Duration ttl;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    final var id = UUID.randomUUID().toString().replace("-", "");
    final var now = Instant.now();

    // Written before the form goes up, so a press can never arrive for a row that does not exist.
    final var row =
        PendingQuestion.builder()
            .id(id)
            .userId(request.userId())
            .chatId(request.chatId())
            .chatType(request.chatType())
            .groupId(request.groupId())
            .tenantId(request.tenantId())
            .conversationId(request.conversationId())
            .rootMessageId(request.rootMessageId())
            // Named for a card and opaque to core: here, the message the form's buttons are on,
            // which is what taking them off again needs.
            .cardId(String.valueOf(message.lastId()))
            .questionsJson(om.writeValueAsString(questions))
            .status(PendingQuestion.Status.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(ttl))
            .build();
    pendingQuestionRepo.save(row);

    updater.showQuestionForm(id, questions);

    // The form may have pushed the reply onto a message of its own, and the buttons go wherever
    // the reply ends.
    final var formId = String.valueOf(message.lastId());
    if (!formId.equals(row.cardId())) {
      pendingQuestionRepo.save(row.toBuilder().cardId(formId).build());
    }
    log.info(
        "Asked {} question(s): pendingQuestionId={}, conversationId={}, message={}",
        questions.size(),
        id,
        request.conversationId(),
        formId);
    return Map.of();
  }
}
