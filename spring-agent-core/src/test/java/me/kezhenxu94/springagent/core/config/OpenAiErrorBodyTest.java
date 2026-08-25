package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * What a rejected model request leaves behind for whoever has to explain the failure.
 *
 * <p>These run against a stub endpoint rather than a provider because the interesting cases are the
 * ones a real OpenAI never produces: a gateway that answers a 400 with an empty body, an HTML error
 * page, or an envelope of its own. Those are what turn a run into {@code BadRequestException: 400:
 * Unknown} — a failure whose cause is stated nowhere in the stack.
 */
class OpenAiErrorBodyTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private MockWebServer server;
  private ListAppender<ILoggingEvent> appender;
  private Logger interceptorLogger;

  @BeforeEach
  void startServer() throws Exception {
    this.server = new MockWebServer();
    this.server.start();
    this.interceptorLogger =
        (Logger) LoggerFactory.getLogger(OpenAiErrorBodyLoggingInterceptor.class);
    this.interceptorLogger.setLevel(Level.WARN);
    this.appender = new ListAppender<>();
    this.appender.start();
    this.interceptorLogger.addAppender(this.appender);
  }

  @AfterEach
  void stopServer() throws Exception {
    this.interceptorLogger.detachAppender(this.appender);
    this.appender.stop();
    this.server.shutdown();
  }

  /**
   * Bodies openai-java cannot parse as JSON at all. Its error handler catches every parse failure
   * and substitutes {@code JsonMissing}, which renders as the bare word {@code "Unknown"} — so for
   * these, and only these, the provider's reason is destroyed.
   */
  static Stream<Arguments> unparseableRejections() {
    return Stream.of(
        Arguments.of("an empty body", "", "text/plain"),
        Arguments.of("a plain-text body", "Bad Request", "text/plain"),
        Arguments.of(
            "an HTML error page",
            "<html><head><title>400 Bad Request</title></head><body>"
                + "<center><h1>400 Bad Request</h1></center><hr><center>nginx</center>"
                + "</body></html>",
            "text/html"));
  }

  /**
   * Bodies that are valid JSON, whether or not they carry the {@code {"error": {...}}} envelope
   * openai-java understands. Since openai-java 4.35.0 these are rendered verbatim into the
   * exception message, so nothing is lost and the interceptor is not what makes them readable.
   * These cases are here to hold that line: they are the reason the diagnosis is specifically "the
   * gateway answered with something that is not JSON", not "error bodies are dropped".
   */
  static Stream<Arguments> parseableRejections() {
    return Stream.of(
        Arguments.of(
            "an envelope of the gateway's own",
            "{\"detail\":\"This model's maximum context length is 65536 tokens\"}",
            "application/json"),
        Arguments.of(
            "the envelope openai-java expects",
            "{\"error\":{\"message\":\"Invalid value for 'stream_options'\","
                + "\"type\":\"invalid_request_error\"}}",
            "application/json"));
  }

  /** Every rejection above, for the assertions that should hold regardless of shape. */
  static Stream<Arguments> allRejections() {
    return Stream.concat(unparseableRejections(), parseableRejections());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unparseableRejections")
  @DisplayName("a rejection that is not JSON arrives with its reason already gone")
  void aRejectionThatIsNotJsonLosesItsReason(
      final String name, final String body, final String contentType) {
    enqueueRejection(body, contentType);

    final var messages = messagesOf(catchStreamFailure(chatModel(false))).toList();

    // The reported symptom, reproduced: the provider said something, and by the time the failure
    // surfaces the only word left is "Unknown".
    assertThat(messages).anyMatch(message -> message.contains("400: Unknown"));
    if (!body.isEmpty()) {
      assertThat(messages)
          .withFailMessage(
              "the SDK is expected to lose the body here; if this starts failing, openai-java "
                  + "began surfacing it and OpenAiErrorBodyLoggingInterceptor can be revisited")
          .noneMatch(message -> message.contains(body));
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("parseableRejections")
  @DisplayName("a rejection that is JSON is surfaced by the SDK without our help")
  void aRejectionThatIsJsonIsSurfacedBySdk(
      final String name, final String body, final String contentType) {
    enqueueRejection(body, contentType);

    final var messages = messagesOf(catchStreamFailure(chatModel(false))).toList();

    assertThat(messages).noneMatch(message -> message.contains("400: Unknown"));
    assertThat(messages)
        .withFailMessage("openai-java renders a parseable error body verbatim since 4.35.0")
        .anyMatch(
            message ->
                message.contains("maximum context length") || message.contains("stream_options"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allRejections")
  @DisplayName("the interceptor logs the body whatever shape it has")
  void theInterceptorLogsWhatTheSdkThrewAway(
      final String name, final String body, final String contentType) {
    enqueueRejection(body, contentType);

    catchStreamFailure(chatModel(true));

    assertThat(this.appender.list).hasSize(1);
    final var logged = this.appender.list.getFirst().getFormattedMessage();
    assertThat(logged).contains("/v1/chat/completions").contains("400").contains("req-abc123");
    assertThat(logged).contains(body.isEmpty() ? "<empty>" : body);
  }

  @Test
  @DisplayName("peeking the body leaves the response readable, so a streamed answer still arrives")
  void peekingLeavesASuccessfulResponseIntact() {
    // A 200 has to pass through with its body still readable. peekBody buffers a copy; a
    // regression to body() would exhaust the stream the SDK is about to parse, and it would show
    // up here — as a broken happy path, not as a broken error path.
    this.server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                """
                data: {"id":"1","object":"chat.completion.chunk","created":1,"model":"stub-model",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},\
                "finish_reason":"stop"}]}

                data: [DONE]

                """));

    final var responses = chatModel(true).stream(new Prompt("hello")).collectList().block(TIMEOUT);

    assertThat(responses).isNotNull().isNotEmpty();
    assertThat(textOf(responses)).contains("hi");
    assertThat(this.appender.list).isEmpty();
  }

  private void enqueueRejection(final String body, final String contentType) {
    this.server.enqueue(
        new MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", contentType)
            .setHeader("x-request-id", "req-abc123")
            .setBody(body));
  }

  private static String textOf(final List<org.springframework.ai.chat.model.ChatResponse> given) {
    return given.stream()
        .filter(response -> response.getResult() != null)
        .map(response -> response.getResult().getOutput().getText())
        .filter(Objects::nonNull)
        .reduce("", String::concat);
  }

  /** Every message in the thrown exception's cause chain. */
  private static Stream<String> messagesOf(final Throwable thrown) {
    return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause)
        .map(cause -> String.valueOf(cause.getMessage()));
  }

  private static Throwable catchStreamFailure(final OpenAiChatModel model) {
    return catchThrowable(() -> model.stream(new Prompt("hello")).blockLast(TIMEOUT));
  }

  /**
   * A chat model pointed at the stub, optionally carrying the interceptor this module contributes —
   * registered through {@code httpClientBuilderCustomizer}, which is the same seam the {@code
   * openAiErrorBodyLoggingCustomizer} bean reaches in a running application.
   */
  private OpenAiChatModel chatModel(final boolean withInterceptor) {
    final var builder =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(this.server.url("/v1").toString())
                    .apiKey("not-a-real-key")
                    .model("stub-model")
                    .build());
    if (withInterceptor) {
      final var interceptor = new OpenAiErrorBodyLoggingInterceptor();
      builder.httpClientBuilderCustomizer(client -> client.interceptor(interceptor));
    }
    return builder.build();
  }
}
