package me.kezhenxu94.springagent.core.tools;

/**
 * Thrown by a question handler when the ask produced no answer, carrying in its message what the
 * model should read instead.
 *
 * <p>Throwing rather than returning, because the answer this stands in for is one the tool insists
 * on: {@code AskUserQuestionTool} wraps whatever a handler returns in "User has answered your
 * questions", which is untrue for an ask that is still out and cannot be changed from outside the
 * library. Spring AI hands a tool exception's message to the model verbatim as the call's result —
 * {@code MethodToolCallback} wraps it as a {@code ToolExecutionException} whose message is this
 * one, and {@code DefaultToolExecutionExceptionProcessor} returns it unchanged — so this is how a
 * note reaches the model as itself.
 *
 * <p>That last step is load-bearing and configurable: naming this type in {@code
 * spring.ai.tools.throw-exception-on-error}, or turning the processor's {@code alwaysThrow} on,
 * would rethrow instead of returning and so fail the run on every unanswered question. {@code
 * SpringAgentTest} asserts the message still arrives as the tool result.
 */
public class QuestionNotAnsweredException extends RuntimeException {

  public QuestionNotAnsweredException(final String note) {
    super(note);
  }
}
