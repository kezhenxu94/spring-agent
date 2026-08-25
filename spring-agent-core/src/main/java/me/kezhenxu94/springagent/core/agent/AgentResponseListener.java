package me.kezhenxu94.springagent.core.agent;

import org.springframework.ai.chat.metadata.Usage;

/**
 * Callbacks for an agent run. There are two ways to get them, and they differ only in reach:
 *
 * <ul>
 *   <li>attached to an {@link AgentRequest}, for the run that request describes;
 *   <li>declared as a bean, for every run — which is how an integration takes part in runs it does
 *       not initiate. Such a bean is normally interested in {@link #onStart} alone, where it can
 *       attach per-run state to the run being assembled.
 * </ul>
 *
 * <p>Every callback is invoked on the thread driving the run, and a callback that throws is logged
 * and ignored rather than failing the run.
 */
public interface AgentResponseListener {

  /**
   * The run has been described but not yet assembled — the last point at which {@code registry} can
   * still contribute listeners, todo handlers or tool-context entries to it. Called on every
   * listener known at that point, which is where a bean listener decides whether this run concerns
   * it at all; a listener that {@code registry} itself attaches arrives too late to be called.
   */
  default void onStart(AgentRunRegistry setup) {}

  default void onSubscribe() {}

  default void onModel(String model) {}

  /** The response accumulated so far, not the latest delta. */
  default void onContent(String contentSoFar) {}

  /**
   * What the model has thought its way through so far, on an endpoint that reports it — everything
   * accumulated across the turn, not the latest delta, and not part of the answer: a surface shows
   * this beside the reply rather than in it.
   *
   * <p>A turn is a loop, so a turn that calls tools thinks more than once. Every call's thinking is
   * kept, one block per call, because the reasoning behind the third tool call is rarely legible
   * without the reasoning that led to the first.
   *
   * <p>Never called at all on an endpoint that reports no reasoning, which is most of them.
   */
  default void onReasoning(String reasoningSoFar) {}

  default void onUsage(String model, Usage usage) {}

  /**
   * Something happened in a run started by this one — so a surface can show work happening
   * somewhere it is not streaming, and say what that work cost. What such a run reports is not this
   * run's answer: that is the result of the tool call that waited for it, and this run says what it
   * made of it.
   *
   * <p>The other way to show a subagent, and the better one where it is available, is to attach to
   * the subagent's own run: a bean listener is asked about every run, including one a tool started,
   * and a listener attached there gets the ordinary callbacks — content, usage, outcome — plus
   * whatever else is per-run, such as a tool-context entry that lets the surface announce the
   * subagent's tool calls too. That is what the Feishu cards do, which is why nothing here
   * implements this. It stays for a surface that cannot: one whose per-run state is tied to
   * something a subagent does not have, a message to reply onto or a terminal to draw in.
   */
  default void onSubagent(SubagentEvent event) {}

  /**
   * One thing that happened in a subagent. Which one is settled by the predicates below rather than
   * by a kind: every event names the subagent, and carries whichever of the rest applies.
   *
   * @param description what the run was started for, in the words of whoever started it
   * @param contentSoFar everything the run has said so far, not the latest delta; set only on an
   *     event reporting what it has said
   * @param model the model of the call this event accounts for, set with {@code usage}
   * @param usage what one model call of that run cost. The same tokens also arrive through {@link
   *     #onUsage}, which is where a surface totals up the turn; this is the same spend attributed
   *     to the subagent that incurred it, for a surface that shows each of them separately. Adding
   *     both to one total counts them twice.
   * @param outcome how the run ended, set only on the event that says it has
   */
  record SubagentEvent(
      String subagentId,
      String description,
      String contentSoFar,
      String model,
      Usage usage,
      AgentOutcome outcome) {

    /** It has been started, and has yet to say or spend anything. */
    public boolean started() {
      return outcome == null && contentSoFar == null && usage == null;
    }

    /** It has said something, and this is all of it so far. */
    public boolean said() {
      return outcome == null && contentSoFar != null;
    }

    /** It has made a model call, and this is what that call cost. */
    public boolean spent() {
      return outcome == null && usage != null;
    }

    /** It has ended, whatever way. */
    public boolean ended() {
      return outcome != null;
    }
  }

  /**
   * A message arrived for this run while it was working, and is waiting to be read at the end of
   * the tool call under way — the first point at which reading it cannot disturb the run's message
   * history. For a surface to show that what the user said was noticed rather than ignored.
   *
   * @param message what they said, as the surface that received it would show it — which is not
   *     necessarily what the model will read: a surface turning a message into something a model
   *     can work with may have to fetch what it carries, and that is not done until the message is
   *     read. Null or empty where the surface has nothing short to show.
   */
  default void onMessageQueued(String message) {}

  /**
   * Everything that was waiting has been read into the run, so the model is working with it now.
   */
  default void onQueuedMessageRead() {}

  default void onError(Throwable error) {}

  /** Invoked exactly once per run, whatever the {@link AgentOutcome}. */
  default void onFinished(AgentOutcome outcome) {}

  /** Returning {@code false} stops consuming the response, ending the run as cancelled. */
  default boolean shouldContinue() {
    return true;
  }
}
