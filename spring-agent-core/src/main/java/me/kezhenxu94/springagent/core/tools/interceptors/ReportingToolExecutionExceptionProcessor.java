package me.kezhenxu94.springagent.core.tools.interceptors;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Hands a tool that failed with a checked exception back to the model as its answer, rather than
 * ending the run.
 *
 * <p>Spring AI's {@code DefaultToolExecutionExceptionProcessor} answers the model for a failure
 * whose cause is a {@code RuntimeException}, and rethrows every other cause, on the reasoning that
 * an {@code Error} must not be swallowed. That puts every {@code IOException} on the wrong side of
 * the line: a tool written with {@code @SneakyThrows} — most of them — that meets a read timeout
 * from the platform it calls aborts the whole turn on {@code Stream processing failed}, throwing
 * away everything the run had done, when the model could simply have called it again. So a checked
 * exception is answered here, saying which tool failed and why, and everything else is left to the
 * delegate: runtime exceptions and the deployment's {@code rethrowExceptions} as Spring AI decides
 * them, and an {@code Error} still thrown.
 *
 * <p>An interruption is the exception to the exception. A cancelled run is stopped by interrupting
 * the thread its tool is blocked on, and answering that failure would hand the model a result and
 * let the run carry on past the stop button. {@link SocketTimeoutException} is an {@link
 * InterruptedIOException} by inheritance only, and is exactly the failure this class exists for.
 *
 * <p>{@code spring.ai.tools.throw-exception-on-error} is honoured, since it asks for every failure
 * to end the run and a checked one is no different.
 */
@Slf4j
@RequiredArgsConstructor
public class ReportingToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

  private final ToolExecutionExceptionProcessor delegate;
  private final boolean alwaysThrow;
  private final CoreMessages messages;

  @Override
  public String process(final ToolExecutionException exception) {
    final var cause = exception.getCause();
    if (!(cause instanceof Exception) || cause instanceof RuntimeException || alwaysThrow) {
      return delegate.process(exception);
    }
    if (interrupted(cause)) {
      throw exception;
    }
    final var name = exception.getToolDefinition().name();
    log.warn("Tool '{}' failed, and the failure was handed back to the model", name, cause);
    return messages.get(
        "tool-failed",
        name,
        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
  }

  private static boolean interrupted(final Throwable cause) {
    return Thread.currentThread().isInterrupted()
        || cause instanceof InterruptedException
        || cause instanceof ClosedByInterruptException
        || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException));
  }
}
