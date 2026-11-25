package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.advisors.StreamStallRetryAdvisor;
import me.kezhenxu94.springagent.core.agent.StreamIdleTimeoutException;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * A streamed answer whose upstream goes silent partway is abandoned after the idle timeout, not
 * after the whole request timeout, and asked for once more where nothing of it had arrived.
 */
class OpenAiStreamIdleTimeoutTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static final Duration IDLE = Duration.ofSeconds(1);

  private static String chunk(final String delta, final String finish) {
    return "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
        + "\"model\":\"own-model\",\"choices\":[{\"index\":0,\"delta\":"
        + delta
        + ",\"finish_reason\":"
        + finish
        + "}]}\n\n";
  }

  /** The opening of an answer, then nothing — what a stalled upstream sends. */
  private static MockResponse stalling(final String first) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(first + chunk("{\"content\":\"late\"}", "\"stop\"") + "data: [DONE]\n\n")
        // The first chunk goes out, and the rest only long after the idle timeout.
        .throttleBody(
            first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 5, TimeUnit.SECONDS);
  }

  private static MockResponse answering() {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null")
                + chunk("{\"content\":\"Hi\"}", "\"stop\"")
                + "data: [DONE]\n\n");
  }

  private static final String OPENING = chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null");

  private MockWebServer server;
  private final List<MockResponse> script = new ArrayList<>();
  private int requests;

  @BeforeEach
  void startServer() throws Exception {
    this.server = new MockWebServer();
    this.server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) {
            return script.get(Math.min(requests++, script.size() - 1));
          }
        });
    this.server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    this.server.shutdown();
  }

  private String ask() {
    final var appOptions =
        OpenAiChatOptions.builder()
            .baseUrl("https://app/v1")
            .apiKey("app-key")
            .model("app-model")
            // The total stays long, as a deployment's does; only the silence is bounded.
            .timeout(Duration.ofMinutes(30))
            .maxRetries(0)
            .build();
    final OpenAiHttpClientBuilderCustomizer idle =
        builder -> builder.interceptor(new OpenAiStreamIdleTimeout(IDLE));
    final ChatClient client =
        new OpenAiUserChatClients(
                OpenAiUserChatClients.Api.CHAT_COMPLETIONS,
                new UserModelRegistry(
                    mock(UserModelConfigRepo.class), new AesGcmSealer(KEY, "t"), 3),
                appOptions,
                ToolCallingManager.builder().build(),
                List.of(idle),
                10)
            .probeClient(
                UserModelConfig.builder()
                    .name("mine")
                    .baseUrl(this.server.url("/v1").toString())
                    .model("own-model")
                    .build(),
                "own-token",
                Map.of());
    return String.join(
        "",
        client
            .mutate()
            .defaultAdvisors(new StreamStallRetryAdvisor())
            .build()
            .prompt()
            .user("hi")
            .stream()
            .content()
            .collectList()
            .block(Duration.ofSeconds(30)));
  }

  @Test
  @DisplayName(
      "a stream that stalls before answering is abandoned after the idle timeout, and asked again")
  void stallThenAnswer() {
    script.add(stalling(OPENING));
    script.add(answering());

    final var started = System.nanoTime();
    assertThat(ask()).isEqualTo("Hi");

    assertThat(requests).isEqualTo(2);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a stream that stalls after part of the answer arrived is not asked again")
  void stallAfterOutput() {
    script.add(stalling(OPENING + chunk("{\"content\":\"Hel\"}", "null")));
    script.add(answering());

    assertThatThrownBy(this::ask).satisfies(error -> assertThat(stalled(error)).isTrue());
    assertThat(requests).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a stream that stalls twice fails with the timeout, for a caller to read as transient")
  void stallTwice() {
    script.add(stalling(OPENING));

    assertThatThrownBy(this::ask).satisfies(error -> assertThat(stalled(error)).isTrue());
    assertThat(requests).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "a streaming request to chat completions or to the Responses API is bounded, and nothing"
          + " else")
  void whichRequestsAreBounded() throws Exception {
    assertThat(OpenAiStreamIdleTimeout.streamingChat(post("/v1/chat/completions", true))).isTrue();
    assertThat(OpenAiStreamIdleTimeout.streamingChat(post("/v1/responses", true))).isTrue();
    assertThat(OpenAiStreamIdleTimeout.streamingChat(post("/v1/responses", false))).isFalse();
    assertThat(OpenAiStreamIdleTimeout.streamingChat(post("/v1/embeddings", true))).isFalse();
  }

  private static okhttp3.Request post(final String path, final boolean stream) {
    return new okhttp3.Request.Builder()
        .url("http://localhost" + path)
        .post(
            okhttp3.RequestBody.create(
                "{\"model\":\"m\",\"stream\":" + stream + "}",
                okhttp3.MediaType.get("application/json")))
        .build();
  }

  private static boolean stalled(final Throwable error) {
    for (var link = error; link != null; link = link.getCause()) {
      if (link instanceof StreamIdleTimeoutException) {
        return true;
      }
    }
    return false;
  }
}
