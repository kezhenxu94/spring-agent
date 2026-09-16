package me.kezhenxu94.springagent.provider.anthropic;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * Logs the body of a failed model response, because nothing else in the stack can.
 *
 * <p>The same finding as {@code OpenAiErrorBodyLoggingInterceptor}, on a second SDK: when the
 * endpoint rejects a request, the reason it gives is destroyed before it reaches a log.
 * anthropic-java's {@code ErrorHandler.errorBodyHandler} parses the body as JSON and catches
 * <em>every</em> exception from that parse, returning {@code JsonMissing}; {@code
 * BadRequestException} then renders its message as the bare {@code "400: Unknown"}. So a gateway
 * that answers with an empty body, an HTML error page or its own envelope produces a run that
 * failed for literally unknowable reasons.
 *
 * <p>It matters more on the Vertex backend than on Anthropic's own. Vertex answers a project
 * without the model enabled, a wrong region and an expired token with three different bodies and
 * two of them are the same status; the body is the only thing that tells them apart.
 *
 * <p>Only the raw bytes answer the question, and this is the one place they still exist.
 */
@Slf4j
public class AnthropicErrorBodyLoggingInterceptor implements Interceptor {

  /**
   * How much of a failed body to keep. A rejection explains itself in the first line or two; a
   * gateway that answers a 400 with a megabyte of HTML should not put that in the log.
   */
  private static final long MAX_BODY_BYTES = 8 * 1024L;

  /**
   * Anthropic's own handle, and not {@code x-request-id} as the OpenAI provider reads: this API
   * spells it without the prefix, and reading the other one logs {@code null} on every failure.
   */
  private static final String REQUEST_ID = "request-id";

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final var request = chain.request();
    final var response = chain.proceed(request);
    if (response.isSuccessful()) {
      return response;
    }

    // peekBody, never body(): the SDK's own error handler reads this response afterwards, and
    // body() would hand it an exhausted stream — turning a diagnosable failure into a different
    // one. peekBody buffers a copy and leaves the original untouched.
    String body;
    try {
      body = response.peekBody(MAX_BODY_BYTES).string();
    } catch (IOException | RuntimeException e) {
      // A body we cannot read is itself the finding, and throwing here would replace the
      // provider's error with ours.
      body = "<unreadable: " + e + ">";
    }

    // Host and path only. A Vertex path carries the project and the model, which is exactly what is
    // worth seeing; a query string is not, and some gateways carry the key in one.
    log.warn(
        "Claude endpoint rejected the request: {} {}{} -> {} (request-id {}). Response body: {}",
        request.method(),
        request.url().host(),
        request.url().encodedPath(),
        response.code(),
        String.valueOf(response.header(REQUEST_ID)),
        body.isBlank() ? "<empty>" : body);

    return response;
  }
}
