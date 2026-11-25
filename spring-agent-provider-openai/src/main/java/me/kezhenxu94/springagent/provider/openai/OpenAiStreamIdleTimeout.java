package me.kezhenxu94.springagent.provider.openai;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import me.kezhenxu94.springagent.core.agent.StreamIdleTimeoutException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/**
 * Abandons a streamed chat answer that has gone silent, rather than waiting out the whole request
 * timeout for it.
 *
 * <p>The request timeout has to be long — a reasoning run streams for minutes — and the SDK uses it
 * as the read timeout too, so a stream whose upstream stopped sending mid-response held its run,
 * and everything queued behind it, for the full half hour before failing. A streamed answer is
 * never silent for long while it is alive: reasoning arrives as deltas, as text does. So on a
 * streaming chat request — chat completions or Responses — the read timeout, which is the longest
 * the socket may go without a byte, is set to this instead, and the timeout it raises is turned
 * into a {@link StreamIdleTimeoutException}, which core retries once where nothing had been
 * answered yet.
 *
 * <p>Set per call, through the interceptor chain, because the client's own read timeout is replaced
 * on every request by one the SDK derives from the request timeout. A non-streaming request is left
 * alone: its whole answer arrives at the end, so silence before it means nothing.
 */
final class OpenAiStreamIdleTimeout implements Interceptor {

  private static final Pattern STREAMING = Pattern.compile("\"stream\"\\s*:\\s*true");

  private final Duration idle;

  OpenAiStreamIdleTimeout(final Duration idle) {
    this.idle = idle;
  }

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final var request = chain.request();
    if (!streamingChat(request)) {
      return chain.proceed(request);
    }
    final Response response;
    try {
      response =
          chain.withReadTimeout((int) idle.toMillis(), TimeUnit.MILLISECONDS).proceed(request);
    } catch (SocketTimeoutException e) {
      throw new StreamIdleTimeoutException(idle, e);
    }
    final var body = response.body();
    return body == null ? response : response.newBuilder().body(new Idle(body, idle)).build();
  }

  /** A POST to the chat completions or Responses endpoint asking for a stream. */
  static boolean streamingChat(final Request request) throws IOException {
    final var path = request.url().encodedPath();
    if (!"POST".equals(request.method())
        || !(path.endsWith("/chat/completions") || path.endsWith("/responses"))
        || request.body() == null) {
      return false;
    }
    final var buffer = new Buffer();
    request.body().writeTo(buffer);
    return STREAMING.matcher(buffer.readUtf8()).find();
  }

  /** The response body, its timeout reported as what it is: the stream going idle. */
  private static final class Idle extends ResponseBody {
    private final ResponseBody delegate;
    private final BufferedSource source;

    private Idle(final ResponseBody delegate, final Duration idle) {
      this.delegate = delegate;
      this.source =
          Okio.buffer(
              new ForwardingSource(delegate.source()) {
                @Override
                public long read(final Buffer sink, final long byteCount) throws IOException {
                  try {
                    return super.read(sink, byteCount);
                  } catch (SocketTimeoutException e) {
                    throw new StreamIdleTimeoutException(idle, e);
                  }
                }
              });
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public BufferedSource source() {
      return source;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
