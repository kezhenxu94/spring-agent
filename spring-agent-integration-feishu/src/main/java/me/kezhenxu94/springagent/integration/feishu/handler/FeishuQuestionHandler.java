package me.kezhenxu94.springagent.integration.feishu.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * conversation back up, and the run ends normally, with a note in chat memory saying what was asked
 * — see {@code SpringAgent.recording}. Whenever the answer arrives, {@link
 * FeishuQuestionAnswerHandler} starts a fresh run on the same conversation, which replays that
 * memory and carries on. An answer after a restart is no different from an answer after five
 * seconds.
 *
 * <p>Per run rather than a bean: the tool's handler interface is handed nothing but the questions,
 * so which conversation they belong to has to be captured when the handler is built.
 */
@Slf4j
@RequiredArgsConstructor
public class FeishuQuestionHandler implements QuestionHandler {

  /**
   * Stands in for the answer the tool expects, since there is not one yet. The tool wraps whatever
   * comes back in "User has answered your questions", which is untrue here and cannot be changed
   * from outside the library — so the value says plainly that it is not an answer, and what to do
   * instead.
   */
  private static final String PENDING =
      "NOT ANSWERED YET. The question has been put to the user, who may take a long time to answer"
          + " or may never answer. Do not guess an answer and do not act as if one was given: stop"
          + " here and end your turn. You will be started again with their answer if it comes.";

  /**
   * Told to a second ask while a first is still unanswered. Firmer than {@link #PENDING}, because
   * anything reaching this has already ignored that once.
   */
  private static final String ALREADY_ASKED =
      "ALREADY ASKED AND STILL UNANSWERED. You have put this to the user and they have not replied"
          + " yet. Asking again does nothing. Stop here and end your turn without calling any"
          + " further tools; you will be started again with their answer if it comes.";

  private final AgentRequest request;
  private final FeishuCardUpdater cardUpdater;
  private final String cardId;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final FeishuQuestionForm questionForm;
  private final JsonMapper om;
  private final Duration ttl;

  @Override
  public Map<String, String> handle(final List<Question> questions) {
    // One outstanding form per conversation. The agent is told to stop and wait when it asks, but a
    // model that asks again anyway — or that cannot see it already asked, which is what happens on
    // a backend whose chat memory drops tool calls — would otherwise stack a second form onto the
    // card and go round again.
    final var outstanding =
        pendingQuestionRepo.findByConversationIdAndStatus(
            request.conversationId(), PendingQuestion.Status.PENDING);
    if (!outstanding.isEmpty()) {
      log.info(
          "Not asking again in conversation {}: {} question(s) already waiting",
          request.conversationId(),
          outstanding.size());
      return answerEach(questions, ALREADY_ASKED);
    }

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
            .cardId(cardId)
            .questionsJson(om.writeValueAsString(questions))
            .status(PendingQuestion.Status.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(ttl))
            .build());

    final var posted = questionForm.insert(cardUpdater, questions, id);
    if (!posted) {
      // Nothing to answer means nothing will ever answer it; leaving the row PENDING would only
      // block the next typed reply from being taken as the answer to something.
      pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.EXPIRED);
      log.warn("Could not put questions to user {}: cardId={}", request.userId(), cardId);
      return failed(questions);
    }

    log.info(
        "Asked {} question(s): pendingQuestionId={}, conversationId={}, cardId={}",
        questions.size(),
        id,
        request.conversationId(),
        cardId);
    return pending(questions);
  }

  private static Map<String, String> pending(final List<Question> questions) {
    return answerEach(questions, PENDING);
  }

  private static Map<String, String> failed(final List<Question> questions) {
    return answerEach(
        questions,
        "COULD NOT ASK. The question could not be shown to the user, so no answer is coming."
            + " Carry on with what you know, and say which assumption you made.");
  }

  /**
   * The tool validates that every question it was given comes back with a value, so each gets the
   * same one rather than a single note that would fail that check.
   */
  private static Map<String, String> answerEach(
      final List<Question> questions, final String value) {
    final var answers = new LinkedHashMap<String, String>();
    for (final var question : questions) {
      answers.put(question.question(), value);
    }
    return answers;
  }
}
