package me.kezhenxu94.springagent.integration.websocket.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
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
  private final ChatReasoningRepo reasonings;
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
    // What it thought goes with them, for the same reason and one more: those rows are the only
    // thing left holding the text of a conversation somebody asked to be rid of.
    chatMemory.clear(session.id());
    reasonings.deleteByConversationId(session.id());
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
   *
   * <p><b>A round's thinking is paired by the digest of its answer</b>, and hung on the user turn
   * that opened the round rather than on the assistant row it was resolved from — that is where a
   * live run draws its fold, and where a reader looks for it. Only the id travels: what was thought
   * is fetched when somebody opens the fold, because it is routinely longer than the whole of the
   * rest of the conversation. See {@link ChatReasoning} for why a digest and not the run's id.
   */
  public List<Turn> transcript(final ChatSession session) {
    final var messages = chatMemory.get(session.id());
    final var thinking = reasoningByAnswer(session.id());
    final var turns = new ArrayList<Turn>();
    // Which turn the round under way was opened by, so an answer found later can hang its thinking
    // there, and -1 where the conversation held back no such turn — memory trimmed to a window
    // beginning mid-round, say.
    var userRow = -1;
    // Where this turn's tools row sits, so a later message's calls are added to it rather than
    // starting a second one, and -1 before the turn has made a call.
    var toolsRow = -1;
    var calls = new ArrayList<ToolUse>();

    for (var position = 0; position < messages.size(); position++) {
      final var message = messages.get(position);
      if (message.getMessageType() == MessageType.USER) {
        // A user message is the turn boundary, and the only one chat memory has: a run begins when
        // somebody says something and ends when the agent stops answering.
        userRow = turns.size();
        turns.add(new Turn("user", message.getText(), List.of(), null));
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
        turns.add(new Turn("assistant", text, List.of(), null));
        // The first answer of the round that matches wins, and a round whose answer matches
        // nothing keeps a null — no thinking shown at all, which is the right answer to "this
        // round produced none" and to "memory no longer holds the answer it was digested from".
        final var requestId = thinking.get(ChatReasoning.digestOf(text));
        if (requestId != null && userRow >= 0 && turns.get(userRow).reasoningId() == null) {
          turns.set(userRow, turns.get(userRow).withReasoning(requestId));
        }
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

      final var row = new Turn("tools", null, List.copyOf(calls), null);
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
   * This conversation's stored thinking, by the digest of the answer each round ended on.
   *
   * <p>A digest two rounds share is dropped rather than kept, which is not a nicety: a conversation
   * whose agent twice answered "Done." would otherwise show one of those rounds the other's
   * reasoning, and be perfectly convincing about it. Ambiguous means nothing is drawn, which is the
   * same answer this gives every other case it cannot be sure of.
   *
   * <p>A row with a blank digest is a run that ended without saying anything — cancelled, or
   * failed. Nothing in a replayed transcript can pair with one, so it is left out here.
   */
  private Map<String, String> reasoningByAnswer(final String conversationId) {
    final var byDigest = new HashMap<String, String>();
    final var ambiguous = new ArrayList<String>();
    for (final var reasoning : reasonings.findByConversationId(conversationId)) {
      final var digest = reasoning.answerDigest();
      if (digest == null || digest.isBlank()) {
        continue;
      }
      if (byDigest.put(digest, reasoning.id()) != null) {
        ambiguous.add(digest);
      }
    }
    ambiguous.forEach(byDigest::remove);
    return byDigest;
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

  /** The longest name somebody may give a conversation, which is what the sidebar row can show. */
  public static final int MAX_TITLE = 120;

  /**
   * What this conversation is called: the name somebody gave it, or the first thing they said.
   *
   * <p>The derived half is derived on read rather than stored, for the reason it always was — a
   * stored copy of the first message is a second copy of something the conversation already
   * contains, and it goes stale the moment the conversation is cleared. A name somebody
   * <em>typed</em> is the opposite case: it is not a copy of anything, so it is stored, and it
   * wins.
   */
  public String titleOf(final ChatSession session) {
    final var named = session.title();
    if (named != null && !named.isBlank()) {
      return named;
    }
    return derivedTitleOf(session);
  }

  /**
   * Renames a conversation, or — given nothing — puts it back to naming itself.
   *
   * <p>Clearing is the same call rather than one of its own, because "call it nothing" and "call it
   * this" are one decision a person makes in one field: emptying the box and pressing enter should
   * give back the name the conversation had before anybody touched it, not leave a blank row that
   * has to be renamed to be readable again.
   */
  public ChatSession rename(final ChatSession session, final String title) {
    final var wanted = title == null ? "" : title.strip();
    final var capped =
        wanted.length() <= MAX_TITLE ? wanted : wanted.substring(0, MAX_TITLE).strip();
    // Not touched: renaming is not something happening in the conversation, and moving it to the
    // top of a list sorted by when it was last used would be the rename pretending to be a turn.
    return sessions.save(session.toBuilder().title(capped).build());
  }

  private String derivedTitleOf(final ChatSession session) {
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
   * @param reasoningId the run whose thinking belongs to this round, on the {@code user} row that
   *     opened it and null everywhere else — including on a round that produced none, or whose
   *     answer this conversation no longer holds. What to ask for, not what was thought: the text
   *     is fetched only if somebody opens the fold
   */
  public record Turn(String role, String text, List<ToolUse> tools, String reasoningId) {

    Turn withReasoning(final String requestId) {
      return new Turn(role, text, tools, requestId);
    }
  }

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
