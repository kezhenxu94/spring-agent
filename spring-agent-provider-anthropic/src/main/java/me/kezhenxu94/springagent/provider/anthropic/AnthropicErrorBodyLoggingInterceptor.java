package me.kezhenxu94.springagent.provider.anthropic;

import java.io.IOException;
import java.util.List;
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
 *
 * <p><b>A 429's body is deliberately unhelpful, which is why this also logs Anthropic's rate-limit
 * headers.</b> A rejection carries {@code {"type":"rate_limit_error","message":"Error"}} — nothing
 * in the body says which of the three limits (requests, input tokens, output tokens) per minute was
 * the one exceeded. Anthropic puts that answer in headers instead, on every response, not only a
 * failed one: {@code anthropic-ratelimit-*-remaining} and {@code -reset} for each of the three,
 * plus {@code retry-after} on the 429 itself. Without them a 429 in the log is a fact with no
 * cause, and raising the account's tier or this module's {@code max-retries} looks like it did
 * nothing when the real ceiling being hit was never named.
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

  /**
   * The headers that answer "which limit, and when does it clear". Read in this order so the log
   * line groups by bucket rather than alphabetically; {@code retry-after} is Anthropic's own
   * suggested wait and is what the SDK's backoff itself reads on a 429.
   */
  private static final List<String> RATE_LIMIT_HEADERS =
      List.of(
          "retry-after",
          "anthropic-ratelimit-requests-remaining",
          "anthropic-ratelimit-requests-reset",
          "anthropic-ratelimit-input-tokens-remaining",
          "anthropic-ratelimit-input-tokens-reset",
          "anthropic-ratelimit-output-tokens-remaining",
          "anthropic-ratelimit-output-tokens-reset",
          "anthropic-ratelimit-tokens-remaining",
          "anthropic-ratelimit-tokens-reset");

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
        "Claude endpoint rejected the request: {} {}{} -> {} (request-id {}). Rate limit: {}."
            + " Response body: {}",
        request.method(),
        request.url().host(),
        request.url().encodedPath(),
        response.code(),
        String.valueOf(response.header(REQUEST_ID)),
        rateLimitHeaders(response),
        body.isBlank() ? "<empty>" : body);

    return response;
  }

  /**
   * The subset of {@link #RATE_LIMIT_HEADERS} this response actually carries, as {@code name=value}
   * pairs. A gateway re-serving the protocol may drop these entirely, which is itself worth seeing
   * rather than a line of {@code name=null}.
   */
  private static String rateLimitHeaders(final Response response) {
    final var present =
        RATE_LIMIT_HEADERS.stream()
            .filter(name -> response.header(name) != null)
            .map(name -> name + "=" + response.header(name))
            .toList();
    return present.isEmpty() ? "<none>" : String.join(", ", present);
  }
}
