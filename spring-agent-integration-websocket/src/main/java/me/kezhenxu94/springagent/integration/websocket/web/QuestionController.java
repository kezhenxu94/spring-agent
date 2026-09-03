package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.run.ChatMirrors;
import me.kezhenxu94.springagent.integration.websocket.run.WebRunListener;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answering what the agent asked.
 *
 * <p>The run that asked is long over — asking ends the turn here — so this is not a reply into
 * anything, it is a new run in the same conversation carrying the answers as its message. Which is
 * exactly why a question survives a reload, a closed tab and a restart: there is no run holding it
 * open, only a row.
 *
 * <p>Mirrors {@code FeishuQuestionAnswerHandler}, including the refusals, which are the interesting
 * part: several people and several tabs can be looking at the same form, and every one of them can
 * press submit.
 */
@Slf4j
@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionController {

  private final SpringAgent springAgent;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final ChatSessions sessions;
  private final ChatMirrors mirrors;
  private final WebMessages messages;
  private final JsonMapper om;

  /**
   * What the form submits: one entry per question, by index.
   *
   * <p>{@code mirror} is the composer's toggle, sent again here for the same reason the composer
   * sends it: answering a question is a turn like any other, and a conversation being followed on a
   * chat should not go quiet just because the last thing the person did was press submit rather
   * than type. Nothing is stored, so the page has to say so each time.
   */
  public record Answers(List<WebQuestions.Submitted> answers, Boolean mirror) {}

  @PostMapping("/{id}/answers")
  public Map<String, Object> answer(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String id,
      @RequestBody final Answers body) {

    final var user = ChatController.user(principal);
    final var pending =
        pendingQuestionRepo
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    // Whose question it was. Not the conversation's owner: the two are the same here, but the
    // question is what carries the identity the answering run will act under.
    if (!user.id().equals(pending.userId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (pending.status() != PendingQuestion.Status.PENDING) {
      // A distinct reason per status, because they mean genuinely different things to the person
      // who just pressed submit: somebody already answered, or their own later message replaced it.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, messages.get("question-" + pending.status().name().toLowerCase()));
    }
    if (pending.expiresAt() != null && pending.expiresAt().isBefore(Instant.now())) {
      pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.EXPIRED);
      throw new ResponseStatusException(HttpStatus.CONFLICT, messages.get("question-expired"));
    }

    final var questions = readQuestions(pending);
    final var answers = WebQuestions.answers(questions, body == null ? List.of() : body.answers());
    if (answers.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("question-empty"));
    }

    // Marked answered before the run is fired, so a second submit racing this one loses rather than
    // starting a second run that argues with the first.
    pendingQuestionRepo.updateStatus(id, PendingQuestion.Status.ANSWERED);

    final var text = asMessage(answers);
    final var requestId = UUID.randomUUID().toString();
    final var session = sessions.ownedBy(pending.conversationId(), user).orElse(null);
    final var builder =
        AgentRequest.builder()
            .requestId(requestId)
            .scenario(BuiltInScenarios.CHAT)
            .userId(user.id())
            .chatId(pending.conversationId())
            .chatType(WebRunListener.CHAT_TYPE)
            // The same reason ChatController gives: groupId scopes the knowledge base and picks
            // the group's home directory, so an answering run without it answers outside the group
            // the conversation is about. Null where the index has no row, which the builder takes
            // as no group — the same as before this line existed.
            .groupId(session == null ? null : session.groupId())
            .tenantId(user.tenantId())
            .conversationId(pending.conversationId())
            .rootMessageId(pending.rootMessageId())
            .replyMessageId(requestId)
            .userMessage(spec -> spec.text(text));
    if (Boolean.TRUE.equals(body == null ? null : body.mirror())) {
      final var mirror = mirrors.forRun(session, user, text);
      if (mirror != null) {
        builder.listener(mirror);
      }
    }
    springAgent.fire(builder.build());

    if (session != null) {
      sessions.touch(session);
    }
    log.info("Question {} answered by {}, continuing as run {}", id, user.id(), requestId);

    return Map.of("requestId", requestId);
  }

  private List<Question> readQuestions(final PendingQuestion pending) {
    return om.readValue(
        pending.questionsJson(),
        om.getTypeFactory().constructCollectionType(List.class, Question.class));
  }

  /**
   * The answers as the model reads them.
   *
   * <p>Question and answer both, not the answers alone: the model asked several things at once and
   * a bare list of labels would leave it matching them up by position — which it gets wrong exactly
   * when a question was skipped.
   */
  private static String asMessage(final Map<String, String> answers) {
    final var text = new StringBuilder();
    answers.forEach(
        (question, answer) -> text.append(question).append('\n').append(answer).append("\n\n"));
    return text.toString().strip();
  }
}
