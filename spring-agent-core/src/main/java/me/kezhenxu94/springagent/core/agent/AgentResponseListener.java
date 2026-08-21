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

  default void onUsage(String model, Usage usage) {}

  /**
   * Something happened in a run started by this one — so a surface can show work happening
   * somewhere it is not streaming, and say what that work cost. What such a run reports is not this
   * run's answer: that is the result of the tool call that waited for it, and this run says what it
   * made of it.
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

  default void onError(Throwable error) {}

  /** Invoked exactly once per run, whatever the {@link AgentOutcome}. */
  default void onFinished(AgentOutcome outcome) {}

  /** Returning {@code false} stops consuming the response, ending the run as cancelled. */
  default boolean shouldContinue() {
    return true;
  }
}
