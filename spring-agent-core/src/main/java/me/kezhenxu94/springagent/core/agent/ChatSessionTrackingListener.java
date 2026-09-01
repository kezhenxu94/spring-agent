package me.kezhenxu94.springagent.core.agent;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link ChatSession} — the index from a person to the conversations they own — in step with
 * every run, whichever surface started it.
 *
 * <p>A bean listener rather than something each surface calls: the index exists so that a surface
 * showing somebody their past conversations can ask for theirs, and a conversation started on one
 * surface has to be findable from another for that to mean anything — a person's Feishu history
 * missing from the web UI they log into with the same identity is the index failing at the one
 * thing it is for. Putting the write here, once, is what keeps every surface agreeing with it
 * instead of each remembering to call it — which is also why, until this existed, only the web UI's
 * own {@code ChatSessions.create()} ever wrote one: nothing else on any other surface did.
 *
 * <p>Written in {@link #onStart}, not deferred to a later callback: this is a stateless upsert
 * keyed entirely on what {@link AgentRequest} already carries, so unlike {@code WebRunListener}
 * there is no per-run object to attach and no reason to register a listener of its own — one bean,
 * called once per run, is the whole of it.
 *
 * <p>Skipped for a background run — a scheduled task's silent check, a subagent, anything with
 * nobody to show a sidebar to — the same distinction {@link AgentRunRegistry#addQuestionHandler}
 * draws and for a related reason: {@code AgentRequest#background()} already means "unattended", and
 * a subagent's own request is background by construction (see {@code SubagentTools}), so this
 * follows that flag rather than inventing a second one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionTrackingListener implements AgentResponseListener {

  private final ChatSessionRepo sessions;

  @Override
  public void onStart(final AgentRunRegistry setup) {
    final var request = setup.request();
    if (request.background()) {
      return;
    }
    final var userId = request.userId();
    final var conversationId = request.conversationId();
    if (userId == null || userId.isBlank() || conversationId == null || conversationId.isBlank()) {
      // Nothing to index: every surface names both, but nothing here guarantees a third party
      // building an AgentRequest by hand does.
      return;
    }
    upsert(userId, conversationId, request.groupId(), request.tenantId());
  }

  private void upsert(
      final String userId,
      final String conversationId,
      final String groupId,
      final String tenantId) {
    try {
      final var existing = sessions.findById(conversationId).orElse(null);
      final var now = Instant.now();
      sessions.save(
          (existing == null
                  ? ChatSession.builder().id(conversationId).userId(userId).createdAt(now)
                  : existing.toBuilder())
              .groupId(groupId)
              .tenantId(tenantId)
              .updatedAt(now)
              .build());
    } catch (final RuntimeException e) {
      // A missing or stale index row costs a conversation invisible to a listing, not a broken
      // run — not worth failing the run over.
      log.warn("Failed to index conversation {} for {}", conversationId, userId, e);
    }
  }
}
