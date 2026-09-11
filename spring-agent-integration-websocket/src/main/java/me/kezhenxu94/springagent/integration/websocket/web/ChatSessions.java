package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
   * <p>Three kinds of row, in the order the conversation holds them: what the person said, what the
   * agent answered, and — where the backend kept them — the tools it called on the way. The live
   * page already draws that third kind from the run journal, which is held in memory and lost on a
   * restart or an eviction; this is the same thing recovered from what was persisted, so that a
   * reloaded conversation still shows why the answer says what it says rather than only that it
   * does.
   *
   * <p><b>One tools row per turn</b>, however many messages the calls were spread over. That is not
   * a tidying decision: a live run draws one fold for the whole run — see {@code toolsPanel} in
   * render.js, which is a slot rather than a row per call — and a turn is what a run leaves behind
   * in chat memory. A row per assistant message would give a reloaded conversation a different
   * shape from the one somebody watched. The row sits where the first call of the turn was made,
   * which is where the fold appears in a live run for the same reason.
   *
   * <p>Tool calling is stored by {@code redis} and {@code mongodb} and dropped by {@code jpa},
   * whose repository is Spring AI's JDBC one. So a conversation with no tools row at all is the
   * ordinary case on one backend rather than a sign anything went wrong.
   *
   * <p><b>A call is paired with its answer by position, not by the id the provider gave it.</b>
   * That id is not an identity on every provider: Spring AI's Gemini model builds every {@code
   * ToolCall} with an empty one, so a turn that called three tools stores three calls that all
   * claim to be the same call. Matching on it showed the last tool's output against all three, and
   * had the page count them as one. Position is sound because the responses to an assistant message
   * are built from its calls in order, and they arrive in the message immediately after it — which
   * is why the pairing is done per message rather than over the whole turn. Where an id <em>is</em>
   * given it is preferred, since a provider that bothers to assign one is the authority on which
   * answer belongs to which call.
   */
  public List<Turn> transcript(final ChatSession session) {
    final var messages = chatMemory.get(session.id());
    final var turns = new ArrayList<Turn>();
    // Where this turn's tools row sits, so a later message's calls are added to it rather than
    // starting a second one, and -1 before the turn has made a call.
    var toolsRow = -1;
    var calls = new ArrayList<ToolUse>();

    for (var position = 0; position < messages.size(); position++) {
      final var message = messages.get(position);
      if (message.getMessageType() == MessageType.USER) {
        // A user message is the turn boundary, and the only one chat memory has: a run begins when
        // somebody says something and ends when the agent stops answering.
        turns.add(new Turn("user", message.getText(), List.of()));
        toolsRow = -1;
        calls = new ArrayList<>();
        continue;
      }
      if (!(message instanceof AssistantMessage assistant)) {
        continue;
      }
      // Text first and tools after, which is the order they happened in: a message carrying both
      // said something and then went looking. Blank rather than merely empty, because an assistant
      // message that only asks for a tool has null text and an empty bubble is worse than no row.
      final var text = assistant.getText();
      if (text != null && !text.isBlank()) {
        turns.add(new Turn("assistant", text, List.of()));
      }
      if (assistant.getToolCalls().isEmpty()) {
        continue;
      }

      final var answers = answersTo(messages, position);
      for (var made = 0; made < assistant.getToolCalls().size(); made++) {
        final var call = assistant.getToolCalls().get(made);
        // Numbered within the row rather than carrying the provider's id, which may be blank and
        // may be blank for every call alike. The page keys one call's result off this, so what it
        // needs is something unique here — the same shape WebRunRenderer gives a live call.
        calls.add(
            new ToolUse(
                "t" + (calls.size() + 1),
                call.name(),
                call.arguments(),
                answerTo(call, made, answers)));
      }

      final var row = new Turn("tools", null, List.copyOf(calls));
      if (toolsRow < 0) {
        toolsRow = turns.size();
        turns.add(row);
      } else {
        turns.set(toolsRow, row);
      }
    }
    return turns;
  }

  /**
   * The responses to the assistant message at {@code position}, which are in the message after it
   * or nowhere. Nowhere is an ordinary case: a run cancelled between the call and its answer, or a
   * memory window that ends there.
   */
  private static List<ToolResponseMessage.ToolResponse> answersTo(
      final List<Message> messages, final int position) {
    if (position + 1 >= messages.size()) {
      return List.of();
    }
    return messages.get(position + 1) instanceof ToolResponseMessage tool
        ? tool.getResponses()
        : List.of();
  }

  /** What one call answered: by its id where it has one, and by where it came otherwise. */
  private static String answerTo(
      final AssistantMessage.ToolCall call,
      final int made,
      final List<ToolResponseMessage.ToolResponse> answers) {
    if (call.id() != null && !call.id().isBlank()) {
      for (final var answer : answers) {
        if (call.id().equals(answer.id())) {
          return answer.responseData();
        }
      }
    }
    return made < answers.size() ? answers.get(made).responseData() : null;
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

  /**
   * One row of the conversation, as the browser reads it.
   *
   * @param role {@code user}, {@code assistant} or {@code tools}
   * @param text what was said, null on a {@code tools} row
   * @param tools the calls one assistant message made, empty on every other role
   */
  public record Turn(String role, String text, List<ToolUse> tools) {}

  /**
   * One tool call and what it answered.
   *
   * @param id its number within the row, and not the provider's own — which is blank on some
   *     providers and blank for every call of a turn alike. The page needs a key it can tell one
   *     call from another by; see {@code transcript}
   * @param result null where the call has no answer in the conversation — the run was cancelled
   *     before it came back, or memory was trimmed to a window that begins after it
   */
  public record ToolUse(String id, String name, String input, String result) {}
}
