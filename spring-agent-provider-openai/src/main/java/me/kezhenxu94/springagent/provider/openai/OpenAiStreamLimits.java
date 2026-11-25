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
import org.springframework.util.unit.DataSize;

/**
 * Bounds a streamed chat answer by two things the request timeout cannot: how long it may go
 * silent, and how much of it may arrive.
 *
 * <p>The request timeout has to be long — a reasoning run streams for minutes — and the SDK uses it
 * as the read timeout too, so a stream whose upstream stopped sending mid-response held its run,
 * and everything queued behind it, for the full half hour before failing. A streamed answer is
 * never silent for long while it is alive: reasoning arrives as deltas, as text does. So once the
 * response has started, the read timeout of its body — the longest the socket may go without a byte
 * — is set to {@code idle}, and the timeout it raises is turned into a {@link
 * StreamIdleTimeoutException}, which core retries once where nothing had been answered yet.
 *
 * <p>The body only, and not the wait for the response headers. That wait is the model reading the
 * prompt, and on a large context a self-hosted model is silent for minutes before its first byte
 * for no fault of its own; cut there, every attempt repeats the same prefill from scratch, and
 * makes it worse. It is also the part of a call the SDK retries on any {@code IOException}, so a
 * timeout raised there would be multiplied by {@code max-retries} on every attempt core makes. A
 * body is read after the SDK has handed the response back, so a stall in it is retried by core
 * alone, once.
 *
 * <p>A stream can also go wrong the other way: a degenerate generation that never ends keeps bytes
 * flowing, so no idle timeout fires, and held its run for the whole request timeout. So the body is
 * abandoned, with a {@link StreamTooLongException}, once more than {@code maxSize} of it has
 * arrived. Measured here in bytes rather than sent as a default {@code max_tokens}: a byte count
 * changes nothing on the wire, where a token limit is a parameter some endpoints refuse ({@code
 * max_tokens} on OpenAI's reasoning models) or ignore, and one that would also silently truncate a
 * long answer into a successful, wrong one rather than fail it. Not retried, since a model that
 * degenerated once on a prompt is as likely to again, and each attempt costs the whole budget.
 *
 * <p>On chat completions and the Responses API alike, set through the interceptor chain on the
 * application's endpoint and every user's. A non-streaming request is left alone: its whole answer
 * arrives at the end, so silence before it means nothing.
 */
final class OpenAiStreamLimits implements Interceptor {

  private static final Pattern STREAMING = Pattern.compile("\"stream\"\\s*:\\s*true");

  /** {@code null} where the silence is not bounded. */
  private final Duration idle;

  /** {@code null} where the size is not bounded. */
  private final DataSize maxSize;

  OpenAiStreamLimits(final Duration idle, final DataSize maxSize) {
    this.idle = idle;
    this.maxSize = maxSize;
  }

  @Override
  public Response intercept(final Chain chain) throws IOException {
    final var request = chain.request();
    final var response = chain.proceed(request);
    final var body = response.body();
    if (body == null || !streamingChat(request)) {
      return response;
    }
    if (idle != null) {
      // Reaches the socket: every source between here and it forwards its timeout, and OkHttp
      // restores the connection's own once the body is done.
      body.source().timeout().timeout(idle.toMillis(), TimeUnit.MILLISECONDS);
    }
    return response.newBuilder().body(new Bounded(body, idle, maxSize)).build();
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

  /** A streamed answer that went past the size a single answer may have. */
  static final class StreamTooLongException extends IOException {
    StreamTooLongException(final DataSize maxSize) {
      super(
          "The model kept sending past "
              + maxSize.toMegabytes()
              + "MB in one answer, so the stream was abandoned as a generation that would not end");
    }
  }

  /** The response body, its timeout reported as what it is and its size counted. */
  private static final class Bounded extends ResponseBody {
    private final ResponseBody delegate;
    private final BufferedSource source;

    private Bounded(final ResponseBody delegate, final Duration idle, final DataSize maxSize) {
      this.delegate = delegate;
      this.source =
          Okio.buffer(
              new ForwardingSource(delegate.source()) {
                private long read;

                @Override
                public long read(final Buffer sink, final long byteCount) throws IOException {
                  final long n;
                  try {
                    n = super.read(sink, byteCount);
                  } catch (SocketTimeoutException e) {
                    throw idle == null ? e : new StreamIdleTimeoutException(idle, e);
                  }
                  if (n > 0 && maxSize != null && (read += n) > maxSize.toBytes()) {
                    throw new StreamTooLongException(maxSize);
                  }
                  return n;
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
