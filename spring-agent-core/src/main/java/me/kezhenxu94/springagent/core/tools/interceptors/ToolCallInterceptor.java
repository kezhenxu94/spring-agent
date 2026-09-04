package me.kezhenxu94.springagent.core.tools.interceptors;

import org.springframework.ai.chat.model.ToolContext;

public interface ToolCallInterceptor {

  default String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
    return toolInput;
  }

  default String afterCall(
      String toolName, String toolInput, String toolResult, ToolContext toolContext) {
    return toolResult;
  }

  /**
   * Raised by {@link #beforeCall} to answer the call instead of making it.
   *
   * <p>An interceptor that decides a call must not happen has nowhere to put that decision: {@code
   * beforeCall} can only rewrite the arguments, and an ordinary exception thrown from here leaves
   * {@code ToolCallback.call} by a path Spring AI's exception processor does not cover, so it
   * aborts the turn rather than reaching the model. This is caught instead and its message becomes
   * the tool's result — the same treatment {@code ToolInputFileRefs.UnresolvableReference} already
   * gets, and for the same reason: a refusal the model can read and explain beats a run that stops.
   *
   * <p>The message is therefore written for the model rather than for a log: say what was refused
   * and what to do about it.
   */
  class CallRefused extends RuntimeException {
    public CallRefused(final String message) {
      super(message);
    }
  }
}
