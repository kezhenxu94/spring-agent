package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

/**
 * A person's conversations: which ones are theirs, and what was said in one.
 *
 * <p>Two stores, deliberately. Ownership is {@link ChatSessionRepo}, which exists only because chat
 * memory cannot answer "whose is this". What was actually said is chat memory itself, read back by
 * the same id — so there is one copy of the transcript and it is the one the model reads, rather
 * than a second copy for the UI that would drift from it the first time memory was trimmed.
 */
@Service
@RequiredArgsConstructor
public class ChatSessions {

  /** How much of the first message becomes the name of a conversation in the sidebar. */
  private static final int TITLE_CHARACTERS = 60;

  private final ChatSessionRepo sessions;
  private final ChatMemory chatMemory;

  public ChatSession create(final WebUser user) {
    final var now = Instant.now();
    return sessions.save(
        ChatSession.builder()
            .id(UUID.randomUUID().toString())
            .userId(user.id())
            .tenantId(user.tenantId())
            .createdAt(now)
            .updatedAt(now)
            .build());
  }

  /** The caller's own conversation, or empty — which is what a request for anyone else's gets. */
  public Optional<ChatSession> ownedBy(final String conversationId, final WebUser user) {
    return sessions.findById(conversationId).filter(it -> user.id().equals(it.userId()));
  }

  public List<ChatSession> listFor(final WebUser user) {
    final Comparator<ChatSession> byUpdatedAt =
        Comparator.comparing(
            ChatSession::updatedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));
    return sessions.findByUserId(user.id()).stream().sorted(byUpdatedAt.reversed()).toList();
  }

  public void touch(final ChatSession session) {
    sessions.save(session.toBuilder().updatedAt(Instant.now()).build());
  }

  public void delete(final ChatSession session) {
    // The index row and the transcript both, or the conversation would be invisible while its
    // contents stayed on disk — and would come back the moment anything re-created the index row.
    chatMemory.clear(session.id());
    sessions.deleteById(session.id());
  }

  /**
   * What was said, as the browser renders it.
   *
   * <p>User and assistant turns only. A tool message is a mechanism rather than a turn, it is not
   * kept by every backend anyway, and the interesting part of it — which tool ran and what it said
   * — is in the run journal where it can be shown as the step it was.
   */
  public List<Turn> transcript(final ChatSession session) {
    return chatMemory.get(session.id()).stream()
        .filter(
            it ->
                it.getMessageType() == MessageType.USER
                    || it.getMessageType() == MessageType.ASSISTANT)
        .map(
            it ->
                new Turn(
                    it.getMessageType() == MessageType.USER ? "user" : "assistant", it.getText()))
        .toList();
  }

  /**
   * The first thing the user said, which is what a conversation is called in the sidebar.
   *
   * <p>Derived on read rather than stored: a stored title is a second copy of something the
   * conversation already contains, and it goes stale the moment the conversation is cleared.
   */
  public String titleOf(final ChatSession session) {
    return chatMemory.get(session.id()).stream()
        .filter(it -> it.getMessageType() == MessageType.USER)
        .map(it -> it.getText() == null ? "" : it.getText().strip())
        .filter(it -> !it.isEmpty())
        .findFirst()
        .map(
            it ->
                it.length() <= TITLE_CHARACTERS
                    ? it
                    : it.substring(0, TITLE_CHARACTERS).strip() + "…")
        .orElse("");
  }

  /** One turn, as the browser reads it. */
  public record Turn(String role, String text) {}
}
