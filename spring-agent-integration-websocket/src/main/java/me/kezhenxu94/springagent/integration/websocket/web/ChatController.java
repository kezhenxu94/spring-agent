package me.kezhenxu94.springagent.integration.websocket.web;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import me.kezhenxu94.springagent.integration.websocket.config.WebLocaleConfiguration;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournals;
import me.kezhenxu94.springagent.integration.websocket.run.WebRunListener;
import me.kezhenxu94.springagent.integration.websocket.security.WebAuthoritiesMapper;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conversations, and the messages that start runs in them.
 *
 * <p>Every method reads the caller's identity from the authenticated principal and checks that what
 * the path names belongs to them. Nothing takes a user id from the client: this application decides
 * whose files, credentials and knowledge base a run may reach out of that one value, so a request
 * able to name its own would be a request able to act as anyone.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

  private final SpringAgent springAgent;
  private final ChatSessions sessions;
  private final RunJournals journals;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final WebMessages messages;
  private final WebProperties properties;
  private final JsonMapper om;

  /**
   * Who the caller is, and whether this deployment serves them.
   *
   * <p>The one endpoint a signed-in person reaches without the role, so that being refused is
   * something the page can explain rather than a wall of failed requests. It reports identity and a
   * verdict and nothing else — never anything the verdict is there to protect.
   */
  @GetMapping("/me")
  public Map<String, Object> me(
      @AuthenticationPrincipal final OAuth2User principal,
      final org.springframework.security.core.Authentication authentication) {
    final var user = user(principal);
    final var allowed =
        authentication != null
            && authentication.getAuthorities().stream()
                .anyMatch(it -> WebAuthoritiesMapper.ROLE.equals(it.getAuthority()));
    final var out = new LinkedHashMap<String, Object>();
    out.put("allowed", allowed);
    // What this deployment calls itself. Reported here rather than from a public endpoint of its
    // own
    // because there is nowhere earlier it could be shown: a caller with no session is redirected
    // straight to the identity provider, so this is the first response the page ever renders from.
    out.put("title", properties.title());
    out.put("userId", user.id());
    out.put("name", user.name());
    out.put("avatar", user.avatar());
    out.put("tenantId", user.tenantId());
    // The language the server resolved for this request, so the page starts in the same one the
    // server is answering in rather than deciding again and disagreeing.
    out.put("locale", messages.locale().toLanguageTag());
    out.put(
        "locales",
        WebLocaleConfiguration.SUPPORTED.stream().map(java.util.Locale::toLanguageTag).toList());
    return out;
  }

  @GetMapping("/conversations")
  public List<Map<String, Object>> conversations(
      @AuthenticationPrincipal final OAuth2User principal) {
    final var user = user(principal);
    final var out = new ArrayList<Map<String, Object>>();
    for (final var session : sessions.listFor(user)) {
      final var item = new LinkedHashMap<String, Object>();
      item.put("id", session.id());
      item.put("title", sessions.titleOf(session));
      item.put("updatedAt", String.valueOf(session.updatedAt()));
      item.put("live", journals.liveByConversationId(session.id()).isPresent());
      out.add(item);
    }
    return out;
  }

  @PostMapping("/conversations")
  public Map<String, Object> newConversation(@AuthenticationPrincipal final OAuth2User principal) {
    final var session = sessions.create(user(principal));
    return Map.of("id", session.id());
  }

  @DeleteMapping("/conversations/{id}")
  public ResponseEntity<Void> deleteConversation(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String id) {
    sessions.delete(mine(id, principal));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/conversations/{id}/messages")
  public List<ChatSessions.Turn> messages(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String id) {
    return sessions.transcript(mine(id, principal));
  }

  /**
   * What a page that has just loaded needs in order to draw the conversation as it stands.
   *
   * <p>This is the reconnect: the browser has the transcript from chat memory, and asks here for
   * the two things the transcript cannot tell it — whether a run is going on right now that it
   * should attach a stream to, and whether the agent is waiting on an answer. Both are true across
   * a refresh; the second is true across a restart of the server as well.
   */
  @GetMapping("/conversations/{id}/state")
  public Map<String, Object> state(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String id) {
    final var session = mine(id, principal);
    final var out = new LinkedHashMap<String, Object>();
    out.put(
        "liveRequestId",
        journals.liveByConversationId(session.id()).map(it -> it.requestId()).orElse(null));
    out.put("pendingQuestion", pendingQuestion(session.id()));
    return out;
  }

  private Map<String, Object> pendingQuestion(final String conversationId) {
    final var pending =
        pendingQuestionRepo
            .findByConversationIdAndStatus(conversationId, PendingQuestion.Status.PENDING)
            .stream()
            .findFirst()
            .orElse(null);
    if (pending == null) {
      return null;
    }
    if (pending.expiresAt() != null && pending.expiresAt().isBefore(java.time.Instant.now())) {
      // Not swept by a job: nothing else has to happen at that moment, so the check belongs where
      // the row is read. The same reasoning PendingQuestion's own comment gives.
      return null;
    }
    final var questions =
        om.readValue(
            pending.questionsJson(),
            om.getTypeFactory()
                .constructCollectionType(
                    List.class,
                    org.springaicommunity.agent.tools.AskUserQuestionTool.Question.class));
    return Map.of(
        "pendingQuestionId", pending.id(),
        "questions", WebQuestions.asJson(castQuestions(questions)));
  }

  @SuppressWarnings("unchecked")
  private static List<org.springaicommunity.agent.tools.AskUserQuestionTool.Question> castQuestions(
      final Object questions) {
    return (List<org.springaicommunity.agent.tools.AskUserQuestionTool.Question>) questions;
  }

  /** What the composer posts. */
  public record Send(String text) {}

  @PostMapping("/conversations/{id}/messages")
  public Map<String, Object> send(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String id,
      @RequestBody final Send body) {
    final var user = user(principal);
    final var session = mine(id, principal);
    final var text = body == null ? null : body.text();
    if (Strings.isNullOrEmpty(text) || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, messages.get("message-empty"));
    }

    // A typed message supersedes a form nobody got round to submitting, so the two cannot both be
    // outstanding — the model would otherwise be answered twice about the same fork.
    supersedePendingQuestions(session.id());

    final var requestId = UUID.randomUUID().toString();
    final var request =
        AgentRequest.builder()
            .requestId(requestId)
            .scenario(BuiltInScenarios.CHAT)
            .userId(user.id())
            .chatId(session.id())
            .chatType(WebRunListener.CHAT_TYPE)
            .tenantId(user.tenantId())
            .conversationId(session.id())
            .rootMessageId(session.id())
            .replyMessageId(requestId)
            .userMessage(spec -> spec.text(text))
            .build();

    // fireOrQueue rather than fire: a message sent while a run is going joins that run and reaches
    // the model mid-turn, so a correction lands before the tool call it was meant to prevent. Only
    // if it cannot does it become a run of its own.
    final var queued = springAgent.fireOrQueue(request, () -> text, text);
    sessions.touch(session);

    return Map.of("requestId", requestId, "queued", queued);
  }

  private void supersedePendingQuestions(final String conversationId) {
    for (final var pending :
        pendingQuestionRepo.findByConversationIdAndStatus(
            conversationId, PendingQuestion.Status.PENDING)) {
      pendingQuestionRepo.updateStatus(pending.id(), PendingQuestion.Status.SUPERSEDED);
    }
  }

  @PostMapping("/runs/{requestId}/cancel")
  public Map<String, Object> cancel(
      @AuthenticationPrincipal final OAuth2User principal, @PathVariable final String requestId) {
    final var user = user(principal);
    final var journal =
        journals
            .byRequestId(requestId)
            .filter(it -> user.id().equals(it.userId()))
            // Deliberately the same answer as "no such run": telling somebody that a run they do
            // not own exists is itself something they should not learn.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    final var stopped = springAgent.cancel(journal.requestId());
    log.info("Stop requested for run {} by {}: {}", requestId, user.id(), stopped);
    return Map.of("stopped", stopped);
  }

  /**
   * The conversation the path names, if it is the caller's.
   *
   * <p>Not found rather than forbidden for somebody else's: a 403 would confirm that the id exists,
   * which is itself something the caller has no business learning.
   */
  private me.kezhenxu94.springagent.core.dao.models.ChatSession mine(
      final String id, final OAuth2User principal) {
    return sessions
        .ownedBy(id, user(principal))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  static WebUser user(final OAuth2User principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return WebUser.of(principal);
  }
}
