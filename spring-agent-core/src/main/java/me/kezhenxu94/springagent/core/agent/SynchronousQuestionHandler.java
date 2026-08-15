package me.kezhenxu94.springagent.core.agent;

/**
 * Marks a question handler that comes back with the user's answer inside the call, rather than
 * putting the questions somewhere and leaving the answer to arrive later.
 *
 * <p>The distinction decides whether the ask ends the turn. A channel that cannot answer within the
 * call has nothing more to say to the model once the questions are up, so the run stops there
 * rather than asking the model to stop itself — which it does not reliably do. A channel that does
 * answer has to keep the turn going, or the answer it just collected would never be acted on.
 *
 * <p>Read before the ask runs, because that is when Spring AI reads {@code returnDirect} off the
 * tool's metadata, so it cannot be decided from what an ask returned.
 */
public interface SynchronousQuestionHandler {}
