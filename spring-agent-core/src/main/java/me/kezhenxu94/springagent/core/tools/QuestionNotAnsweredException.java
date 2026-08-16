package me.kezhenxu94.springagent.core.tools;

/**
 * Thrown by a question handler when the ask produced no answer, carrying in its message what the
 * model should read instead.
 *
 * <p>Thrown rather than returned, because {@code AskUserQuestionTool} wraps whatever a handler
 * returns in "User has answered your questions" — untrue for an ask that is still out, and not
 * changeable from outside the library. Spring AI hands a tool exception's message to the model
 * verbatim, so this is how a note reaches it as itself.
 *
 * <p>That last step is configurable and load-bearing: naming this type in {@code
 * spring.ai.tools.throw-exception-on-error} would fail the run on every unanswered question
 * instead. {@code SpringAgentTest} asserts the message still arrives as the tool result.
 */
public class QuestionNotAnsweredException extends RuntimeException {

  public QuestionNotAnsweredException(final String note) {
    super(note);
  }
}
