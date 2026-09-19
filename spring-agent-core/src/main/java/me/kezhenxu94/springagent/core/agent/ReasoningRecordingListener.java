package me.kezhenxu94.springagent.core.agent;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keeps what a run thought, so that it outlives the run.
 *
 * <p>Reasoning is streamed while a run is going and then gone — a card's panel, a browser's fold,
 * an in-memory journal that is evicted. A deployment that turns the Feishu card's panel off had
 * nowhere left to see it at all. So it is written down here, once, for every surface: a bean
 * listener rather than something each surface remembers to do, exactly as {@link
 * ChatSessionTrackingListener} is, and for the same reason — a round answered on one surface is
 * read back on another.
 *
 * <p>Unlike that one this has per-run state to keep, so {@link #onStart} attaches a listener rather
 * than writing anything itself: the row is written once the run has finished, because what is
 * stored is the whole of the turn's thinking and only the last callback has all of it.
 *
 * <p>Skipped for a background run — a subagent, a scheduled task's silent check — the same
 * distinction {@link ChatSessionTrackingListener} draws, and a subagent is background by
 * construction (see {@code SubagentTools}). A subagent's thinking belongs to the tool call that
 * started it rather than to the round, and nothing shows it beside the round.
 *
 * <p>{@code app.ai.reasoning.store} turns it off for a deployment that does not want the bytes.
 * Nothing is truncated when it is on: see {@link ChatReasoning#text}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.reasoning.store", havingValue = "true", matchIfMissing = true)
public class ReasoningRecordingListener implements AgentResponseListener {

  private final ChatReasoningRepo reasonings;

  @Override
  public void onStart(final AgentRunRegistry setup) {
    final var request = setup.request();
    if (request.background()) {
      return;
    }
    final var conversationId = request.conversationId();
    if (conversationId == null || conversationId.isBlank()) {
      // Nowhere to hang it: a row is found again by the conversation it belongs to, and a run
      // without one could only ever be read back by an id nobody kept.
      return;
    }
    final var requestId = request.requestId();
    setup.addResponseListener(
        new Recorder(
            reasonings,
            // A request whose id somebody left out still gets a row of its own rather than
            // overwriting whichever row already has an empty id. Every surface here sets one; a
            // third party building an AgentRequest by hand need not.
            requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
            conversationId,
            request.userId()));
  }

  /**
   * One run's thinking, accumulated and written at the end.
   *
   * <p>Both callbacks hand over everything so far rather than the latest delta, so keeping the last
   * of each is keeping all of it — see {@link AgentResponseListener#onReasoning}.
   */
  @RequiredArgsConstructor
  static final class Recorder implements AgentResponseListener {

    private final ChatReasoningRepo reasonings;
    private final String requestId;
    private final String conversationId;
    private final String userId;

    private volatile String reasoning;
    private volatile String answer;

    @Override
    public void onReasoning(final String reasoningSoFar) {
      reasoning = reasoningSoFar;
    }

    @Override
    public void onContent(final String contentSoFar) {
      answer = contentSoFar;
    }

    /**
     * Whatever the outcome: a run that was cancelled or failed thought its way to wherever it got
     * to, and that is often the most interesting thing about it. A run that reported no reasoning
     * at all — which is most providers — writes nothing, so a row existing means there is something
     * to read.
     */
    @Override
    public void onFinished(final AgentOutcome outcome) {
      if (reasoning == null || reasoning.isBlank()) {
        return;
      }
      try {
        reasonings.save(
            ChatReasoning.builder()
                .id(requestId)
                .conversationId(conversationId)
                .userId(userId)
                .answerDigest(ChatReasoning.digestOf(answer))
                .text(reasoning)
                .createdAt(Instant.now())
                .build());
      } catch (final RuntimeException e) {
        // Costs a round whose thinking cannot be read back, not a run — and the run is over by
        // now in any case, so there is nothing left to fail.
        log.warn("Failed to record the reasoning of {} in {}", requestId, conversationId, e);
      }
    }
  }
}
