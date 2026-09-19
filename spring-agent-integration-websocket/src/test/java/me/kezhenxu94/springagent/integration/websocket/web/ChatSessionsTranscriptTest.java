package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * How a stored conversation becomes the rows a reloaded page draws.
 *
 * <p>The interesting half is tool calling, which only reaches here on a backend that keeps it —
 * chat memory is mocked rather than run against one, because what is under test is the shaping and
 * not which backend stored what.
 */
class ChatSessionsTranscriptTest {

  private static final String ID = "c1";

  private final ChatMemory chatMemory = mock(ChatMemory.class);

  /** The rows a run left behind, over a list: what is under test is the pairing, not a backend. */
  private final List<ChatReasoning> recorded = new ArrayList<>();

  private final ChatReasoningRepo reasonings =
      new ChatReasoningRepo() {
        @Override
        public ChatReasoning save(final ChatReasoning reasoning) {
          recorded.add(reasoning);
          return reasoning;
        }

        @Override
        public Optional<ChatReasoning> findById(final String id) {
          return recorded.stream().filter(it -> it.id().equals(id)).findFirst();
        }

        @Override
        public List<ChatReasoning> findByConversationId(final String conversationId) {
          return recorded.stream()
              .filter(it -> conversationId.equals(it.conversationId()))
              .toList();
        }

        @Override
        public void deleteByConversationId(final String conversationId) {
          recorded.removeIf(it -> conversationId.equals(it.conversationId()));
        }
      };

  private final ChatSessions sessions =
      new ChatSessions(mock(ChatSessionRepo.class), reasonings, chatMemory);

  /**
   * A run that thought its way to {@code answer}, as the recording listener would have stored it.
   */
  private void thought(final String requestId, final String answer) {
    reasonings.save(
        ChatReasoning.builder()
            .id(requestId)
            .conversationId(ID)
            .userId("me")
            .answerDigest(ChatReasoning.digestOf(answer))
            .text("thinking of " + requestId)
            .build());
  }

  private final ChatSession session = ChatSession.builder().id(ID).userId("me").build();

  @Test
  @DisplayName("a call is shown beside what it answered")
  void aCallCarriesItsResult() {
    given(
        new UserMessage("weather?"),
        AssistantMessage.builder()
            .content(null)
            .toolCalls(List.of(new AssistantMessage.ToolCall("c-1", "function", "weather", "{}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("c-1", "weather", "21")))
            .build(),
        new AssistantMessage("21 degrees."));

    final var turns = sessions.transcript(session);

    // Three rows, not four: a tool message is not a row of its own, and the assistant message that
    // only asked for the tool contributes no bubble because it has nothing to say.
    assertThat(turns)
        .extracting(ChatSessions.Turn::role)
        .containsExactly("user", "tools", "assistant");
    assertThat(turns.get(1).tools())
        .containsExactly(new ChatSessions.ToolUse("t1", "weather", "{}", "21"));
  }

  @Test
  @DisplayName("a call the conversation has no answer to is still shown, without one")
  void anUnansweredCallIsNotDropped() {
    // What a cancelled run leaves behind, and what memory trimmed to a window beginning mid-turn
    // looks like. Hiding the call would make the conversation say the agent never tried.
    given(
        new UserMessage("weather?"),
        AssistantMessage.builder()
            .content(null)
            .toolCalls(List.of(new AssistantMessage.ToolCall("c-9", "function", "weather", "{}")))
            .build());

    final var turns = sessions.transcript(session);

    assertThat(turns).extracting(ChatSessions.Turn::role).containsExactly("user", "tools");
    assertThat(turns.get(1).tools())
        .singleElement()
        .satisfies(it -> assertThat(it.result()).isNull());
  }

  @Test
  @DisplayName("a message that both answered and went looking keeps its order")
  void textComesBeforeTheCallItLedTo() {
    given(
        new UserMessage("weather?"),
        AssistantMessage.builder()
            .content("Let me look.")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c-2", "function", "weather", "{}")))
            .build());

    assertThat(sessions.transcript(session))
        .extracting(ChatSessions.Turn::role)
        .containsExactly("user", "assistant", "tools");
  }

  @Test
  @DisplayName("a conversation from a backend that keeps no tool calls is unchanged")
  void aBackendWithoutToolCallsReadsAsBefore() {
    // jpa, where Spring AI's JDBC repository drops them. An empty tools list on every row is the
    // ordinary case there, and the page draws exactly what it drew before.
    given(new UserMessage("hi"), new AssistantMessage("Hello."));

    assertThat(sessions.transcript(session))
        .extracting(ChatSessions.Turn::role, ChatSessions.Turn::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("user", "hi"),
            org.assertj.core.groups.Tuple.tuple("assistant", "Hello."));
  }

  @Test
  @DisplayName("a turn's calls are one row however many messages made them")
  void oneToolsRowPerTurn() {
    // The shape a live run has: one fold for the whole run, counting up as calls arrive — see
    // toolsPanel in render.js. A multi-step turn is several assistant messages in chat memory, and
    // a row for each would give a reloaded conversation a shape nobody watched.
    given(
        new UserMessage("tidy up"),
        asking(new AssistantMessage.ToolCall("c-1", "function", "ls", "{}")),
        answered("c-1", "a b"),
        asking(new AssistantMessage.ToolCall("c-2", "function", "rm", "{}")),
        answered("c-2", "gone"),
        new AssistantMessage("Tidied."));

    final var turns = sessions.transcript(session);

    assertThat(turns)
        .extracting(ChatSessions.Turn::role)
        .containsExactly("user", "tools", "assistant");
    assertThat(turns.get(1).tools())
        .extracting(ChatSessions.ToolUse::name, ChatSessions.ToolUse::result)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("ls", "a b"),
            org.assertj.core.groups.Tuple.tuple("rm", "gone"));
  }

  @Test
  @DisplayName("the next turn's calls start a row of their own")
  void aTurnDoesNotInheritTheLastOne() {
    // The other half of the same rule: a turn is a run, and two runs are two folds.
    given(
        new UserMessage("one"),
        asking(new AssistantMessage.ToolCall("c-1", "function", "ls", "{}")),
        answered("c-1", "a"),
        new AssistantMessage("Done."),
        new UserMessage("two"),
        asking(new AssistantMessage.ToolCall("c-2", "function", "ls", "{}")),
        answered("c-2", "b"),
        new AssistantMessage("Done again."));

    final var turns = sessions.transcript(session);

    assertThat(turns)
        .extracting(ChatSessions.Turn::role)
        .containsExactly("user", "tools", "assistant", "user", "tools", "assistant");
    // And each row numbers its own calls from one, since that number is only ever a key the page
    // tells one call of a row from another by.
    assertThat(turns.get(1).tools())
        .containsExactly(new ChatSessions.ToolUse("t1", "ls", "{}", "a"));
    assertThat(turns.get(4).tools())
        .containsExactly(new ChatSessions.ToolUse("t1", "ls", "{}", "b"));
  }

  @Test
  @DisplayName("a provider that gives every call the same blank id still pairs them up")
  void blankIdsArePairedByPosition() {
    // Spring AI's Gemini model builds every ToolCall with an empty id. Matching on it showed the
    // last tool's output against every call, and had the page count three calls as one — the ids
    // are the key it tells them apart by. The responses to one message are built from its calls in
    // order, so position is what actually says which is which.
    given(
        new UserMessage("go"),
        AssistantMessage.builder()
            .content(null)
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall("", "function", "ls", "{}"),
                    new AssistantMessage.ToolCall("", "function", "pwd", "{}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse("", "ls", "a b"),
                    new ToolResponseMessage.ToolResponse("", "pwd", "/root")))
            .build());

    assertThat(sessions.transcript(session).get(1).tools())
        .containsExactly(
            new ChatSessions.ToolUse("t1", "ls", "{}", "a b"),
            new ChatSessions.ToolUse("t2", "pwd", "{}", "/root"));
  }

  @Test
  @DisplayName("an id a provider did bother to give is what decides, not the order")
  void anIdWinsOverPosition() {
    // OpenAI assigns one, and a provider that does is the authority on which answer is whose.
    given(
        new UserMessage("go"),
        AssistantMessage.builder()
            .content(null)
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall("call_a", "function", "ls", "{}"),
                    new AssistantMessage.ToolCall("call_b", "function", "pwd", "{}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse("call_b", "pwd", "/root"),
                    new ToolResponseMessage.ToolResponse("call_a", "ls", "a b")))
            .build());

    assertThat(sessions.transcript(session).get(1).tools())
        .extracting(ChatSessions.ToolUse::name, ChatSessions.ToolUse::result)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("ls", "a b"),
            org.assertj.core.groups.Tuple.tuple("pwd", "/root"));
  }

  @Test
  @DisplayName("a round's thinking hangs on the message that started it")
  void reasoningIsPairedToTheRoundThatProducedIt() {
    thought("r-1", "Hello.");
    given(new UserMessage("hi"), new AssistantMessage("Hello."));

    final var turns = sessions.transcript(session);

    // On the user row rather than the assistant one it was resolved from: that is where a live run
    // draws its fold, and where a reader looks for it.
    assertThat(turns)
        .extracting(ChatSessions.Turn::role, ChatSessions.Turn::reasoningId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("user", "r-1"),
            org.assertj.core.groups.Tuple.tuple("assistant", null));
  }

  @Test
  @DisplayName("each round gets its own, and a round that thought nothing gets none")
  void eachRoundKeepsItsOwn() {
    thought("r-2", "Two.");
    given(
        new UserMessage("one"),
        new AssistantMessage("One."),
        new UserMessage("two"),
        new AssistantMessage("Two."));

    assertThat(sessions.transcript(session))
        .extracting(ChatSessions.Turn::reasoningId)
        .containsExactly(null, null, "r-2", null);
  }

  @Test
  @DisplayName("two rounds that answered identically show neither's thinking")
  void anAmbiguousAnswerPairsWithNothing() {
    // The failure mode a digest has and a run id would not: an agent that twice answered "Done."
    // leaves two rows a replayed turn cannot be told apart by. Showing one of them would be
    // showing the wrong round's reasoning, convincingly, so nothing is shown.
    thought("r-1", "Done.");
    thought("r-2", "Done.");
    given(
        new UserMessage("one"),
        new AssistantMessage("Done."),
        new UserMessage("two"),
        new AssistantMessage("Done."));

    assertThat(sessions.transcript(session))
        .extracting(ChatSessions.Turn::reasoningId)
        .containsOnlyNulls();
  }

  @Test
  @DisplayName("a run that ended without saying anything pairs with nothing")
  void aRunThatSaidNothingPairsWithNothing() {
    // Cancelled before it answered. The row is worth keeping for a surface that still holds the
    // run's id, but its blank digest must not pair with a turn whose text is somehow empty too.
    thought("r-1", "");
    given(new UserMessage("hi"), new AssistantMessage("Hello."));

    assertThat(sessions.transcript(session))
        .extracting(ChatSessions.Turn::reasoningId)
        .containsOnlyNulls();
  }

  @Test
  @DisplayName("deleting a conversation takes what it thought with it")
  void deletingTakesTheThinkingToo() {
    thought("r-1", "Hello.");

    sessions.delete(session);

    assertThat(recorded).isEmpty();
  }

  private static AssistantMessage asking(final AssistantMessage.ToolCall call) {
    return AssistantMessage.builder().content(null).toolCalls(List.of(call)).build();
  }

  private static ToolResponseMessage answered(final String id, final String data) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse(id, "tool", data)))
        .build();
  }

  private void given(final Message... messages) {
    when(chatMemory.get(ID)).thenReturn(List.of(messages));
  }
}
