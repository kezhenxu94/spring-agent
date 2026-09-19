package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What is kept of a run's thinking, and what is deliberately not.
 *
 * <p>Over a map rather than a backend, because what is being asserted is the listener's own
 * decision about which runs leave a row and what goes in it — the same decision on all three
 * backends, since none of them sees it.
 */
class ReasoningRecordingListenerTest {

  private final Map<String, ChatReasoning> rows = new LinkedHashMap<>();

  private final ChatReasoningRepo repo =
      new ChatReasoningRepo() {
        @Override
        public ChatReasoning save(final ChatReasoning reasoning) {
          rows.put(reasoning.id(), reasoning);
          return reasoning;
        }

        @Override
        public Optional<ChatReasoning> findById(final String id) {
          return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ChatReasoning> findByConversationId(final String conversationId) {
          return rows.values().stream().filter(it -> conversationId.equals(it.conversationId()))
              .toList();
        }

        @Override
        public void deleteByConversationId(final String conversationId) {
          rows.values().removeIf(it -> conversationId.equals(it.conversationId()));
        }
      };

  private final ReasoningRecordingListener listener = new ReasoningRecordingListener(repo);

  /** Runs a turn through the listener the way {@code SpringAgent} would, and returns what it kept. */
  private List<AgentResponseListener> start(final AgentRequest request) {
    final var registry = new AgentRunRegistry(request);
    listener.onStart(registry);
    return registry.responseListeners();
  }

  private static AgentRequest.AgentRequestBuilder request() {
    return AgentRequest.builder()
        .requestId("r-1")
        .scenario(BuiltInScenarios.CHAT)
        .userId("ou_1")
        .conversationId("conv-1")
        .userMessage(user -> user.text("why is it down?"));
  }

  @Test
  @DisplayName("a round that thought leaves one row, keyed by the run and digested by its answer")
  void recordsTheTurnsThinking() {
    final var attached = start(request().build());
    assertThat(attached).hasSize(1);

    final var recorder = attached.getFirst();
    // Both callbacks hand over everything so far rather than the latest delta, so the last one
    // wins rather than being appended to.
    recorder.onReasoning("First I");
    recorder.onContent("The disk");
    recorder.onReasoning("First I should check the disk.");
    recorder.onContent("The disk was full.");
    recorder.onFinished(AgentOutcome.COMPLETED);

    assertThat(rows.get("r-1"))
        .returns("conv-1", ChatReasoning::conversationId)
        .returns("ou_1", ChatReasoning::userId)
        .returns("First I should check the disk.", ChatReasoning::text)
        .returns(ChatReasoning.digestOf("The disk was full."), ChatReasoning::answerDigest);
  }

  @Test
  @DisplayName("a run that was stopped still kept what it had thought up to then")
  void recordsACancelledRun() {
    final var recorder = start(request().build()).getFirst();
    recorder.onReasoning("I should look at the logs");
    recorder.onFinished(AgentOutcome.CANCELLED);

    assertThat(rows.get("r-1").text()).isEqualTo("I should look at the logs");
    // Nothing was said, so nothing can pair a replayed turn to this row — and a blank digest is
    // what stops it pairing with a turn whose answer happens to be empty too.
    assertThat(rows.get("r-1").answerDigest()).isEmpty();
  }

  @Test
  @DisplayName("an endpoint that reports no thinking leaves no row, so a row means there is something to read")
  void recordsNothingWhereThereWasNoReasoning() {
    final var recorder = start(request().build()).getFirst();
    recorder.onContent("The disk was full.");
    recorder.onFinished(AgentOutcome.COMPLETED);

    assertThat(rows).isEmpty();
  }

  @Test
  @DisplayName("an unattended run — a subagent, a scheduled check — is nobody's round")
  void skipsBackgroundRuns() {
    assertThat(start(request().background(true).build())).isEmpty();
  }

  @Test
  @DisplayName("a run belonging to no conversation has nowhere to be read back from")
  void skipsRunsWithoutAConversation() {
    assertThat(start(request().conversationId(null).build())).isEmpty();
  }
}
