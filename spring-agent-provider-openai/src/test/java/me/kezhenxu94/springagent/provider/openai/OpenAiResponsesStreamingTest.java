package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * A row on the Responses API against an endpoint that serves nothing else, and that only when
 * streaming — the shape some OpenAI-compatible endpoints have for some of their models. A run
 * streams, calls a tool, sends its reasoning effort and reads usage back, as on chat completions.
 */
class OpenAiResponsesStreamingTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static final String USAGE =
      """
      "usage":{"input_tokens":12,"output_tokens":5,"total_tokens":17,\
      "input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":2}}\
      """;

  private MockWebServer server;
  private final List<RecordedRequest> requests = new ArrayList<>();

  /** Each request's body, read once as it arrives: a recorded body can only be read once. */
  private final List<String> bodies = new ArrayList<>();

  @BeforeEach
  void startServer() throws Exception {
    this.server = new MockWebServer();
    this.server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) {
            requests.add(request);
            if (!"/v1/responses".equals(request.getPath())) {
              return json(404, "{\"detail\":\"Not Found\"}");
            }
            final var text = request.getBody().readUtf8();
            bodies.add(text);
            final var body = JsonMapper.builder().build().readTree(text);
            if (!body.path("stream").asBoolean(false)) {
              return json(400, "{\"detail\":\"Stream must be set to true\"}");
            }
            return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                // A tool call where tools were offered and none has been answered yet; text
                // otherwise, which is also what a probe offering no tools is answered with.
                .setBody(
                    body.path("tools").size() > 0 && !text.contains("function_call_output")
                        ? toolCallStream()
                        : answerStream());
          }
        });
    this.server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    this.server.shutdown();
  }

  @Test
  @DisplayName(
      "a run streams from /responses, calls a tool, sends its effort and reads usage, on an"
          + " endpoint serving only that")
  void streamsToolsEffortAndUsage() throws Exception {
    final var client =
        clients()
            .probeClient(
                UserModelConfig.builder()
                    .name("mine")
                    .baseUrl(this.server.url("/v1").toString())
                    .model("own-model")
                    .reasoningEffort("high")
                    .build(),
                "own-token",
                Map.of());

    final List<ChatResponse> responses =
        client
            .prompt()
            .user("what is the weather in Paris")
            .tools(new OpenAiUserModelToolsTest.Forecast())
            .stream()
            .chatResponse()
            .collectList()
            .block();

    final var text =
        responses.stream()
            .filter(r -> r.getResult() != null && r.getResult().getOutput().getText() != null)
            .map(r -> r.getResult().getOutput().getText())
            .reduce("", String::concat);
    assertThat(text).contains("Sunny");
    assertThat(responses)
        .anySatisfy(r -> assertThat(r.getMetadata().getUsage().getTotalTokens()).isEqualTo(17));

    assertThat(requests)
        .hasSize(2)
        .allSatisfy(r -> assertThat(r.getPath()).isEqualTo("/v1/responses"));
    final var first = JsonMapper.builder().build().readTree(bodies.get(0));
    assertThat(first.path("stream").asBoolean()).isTrue();
    assertThat(first.path("reasoning").path("effort").asString()).isEqualTo("high");
    assertThat(first.path("tools").toString()).contains("\"name\":\"forecast\"");
    // The tool ran, and its answer went back on the second request.
    assertThat(bodies.get(1)).contains("function_call_output").contains("sunny in Paris");
  }

  @Test
  @DisplayName(
      "registering it over chat completions says the endpoint answers over the Responses API, and"
          + " registering it over that passes")
  void probeNamesTheApiThatAnswers() {
    final var registry =
        new UserModelRegistry(mock(UserModelConfigRepo.class), new AesGcmSealer(KEY, "t"), 3);
    final var dispatching =
        new me.kezhenxu94.springagent.core.usermodels.DispatchingUserChatClients(
            mock(org.springframework.ai.chat.client.ChatClient.class),
            registry,
            List.of(clients(OpenAiUserChatClients.Api.CHAT_COMPLETIONS), clients()),
            "openai");
    final var source = new org.springframework.context.support.ResourceBundleMessageSource();
    source.setBasename(me.kezhenxu94.springagent.core.config.CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    final var properties =
        new me.kezhenxu94.springagent.core.config.SpringAgentProperties(
            new me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai(
                java.util.Set.of(),
                null,
                Map.of(),
                null,
                null,
                null,
                "You are a test.",
                null,
                null,
                null),
            java.util.Locale.ENGLISH,
            null,
            null);
    final var probe =
        new me.kezhenxu94.springagent.core.usermodels.UserModelProbe(
            dispatching,
            new me.kezhenxu94.springagent.core.config.CoreMessages(source, properties),
            java.time.Duration.ofSeconds(10),
            properties);
    final var url = this.server.url("/v1").toString();

    assertThat(probe.check("openai", url, "own-model", "own-token", null, null, false, Map.of()))
        .contains("404")
        .contains("Register it with the protocol openai-responses");
    assertThat(
            probe.check(
                "openai-responses", url, "own-model", "own-token", null, null, false, Map.of()))
        .isNull();
  }

  private static MockResponse json(final int status, final String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static String response(final String id, final String status, final String output) {
    return """
    {"id":"%s","object":"response","created_at":1,"model":"own-model","status":"%s",\
    "output":[%s],"parallel_tool_calls":true,"tool_choice":"auto","tools":[],"metadata":{},\
    "error":null,"incomplete_details":null,"instructions":null,"temperature":1,"top_p":1,%s}\
    """
        .formatted(id, status, output, USAGE);
  }

  private static String event(final String type, final String data) {
    return "event: " + type + "\ndata: " + data + "\n\n";
  }

  private static String toolCallStream() {
    final var call =
        """
        {"type":"function_call","id":"fc_1","call_id":"call_1","name":"forecast",\
        "arguments":"{\\"city\\":\\"Paris\\"}","status":"completed"}\
        """;
    return event(
            "response.created",
            "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                + response("resp_1", "in_progress", "")
                + "}")
        + event(
            "response.output_item.added",
            "{\"type\":\"response.output_item.added\",\"sequence_number\":1,\"output_index\":0,"
                + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\","
                + "\"name\":\"forecast\",\"arguments\":\"\",\"status\":\"in_progress\"}}")
        + event(
            "response.function_call_arguments.delta",
            "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":2,"
                + "\"output_index\":0,\"item_id\":\"fc_1\","
                + "\"delta\":\"{\\\"city\\\":\\\"Paris\\\"}\"}")
        + event(
            "response.output_item.done",
            "{\"type\":\"response.output_item.done\",\"sequence_number\":3,\"output_index\":0,"
                + "\"item\":"
                + call
                + "}")
        + event(
            "response.completed",
            "{\"type\":\"response.completed\",\"sequence_number\":4,\"response\":"
                + response("resp_1", "completed", call)
                + "}");
  }

  private static String answerStream() {
    final var message =
        """
        {"type":"message","id":"msg_1","role":"assistant","status":"completed",\
        "content":[{"type":"output_text","text":"Sunny","annotations":[]}]}\
        """;
    return event(
            "response.created",
            "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                + response("resp_2", "in_progress", "")
                + "}")
        + event(
            "response.output_text.delta",
            "{\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"item_id\":\"msg_1\","
                + "\"output_index\":0,\"content_index\":0,\"delta\":\"Sunny\",\"logprobs\":[]}")
        + event(
            "response.output_item.done",
            "{\"type\":\"response.output_item.done\",\"sequence_number\":2,\"output_index\":0,"
                + "\"item\":"
                + message
                + "}")
        + event(
            "response.completed",
            "{\"type\":\"response.completed\",\"sequence_number\":3,\"response\":"
                + response("resp_2", "completed", message)
                + "}");
  }

  private static OpenAiUserChatClients clients() {
    return clients(OpenAiUserChatClients.Api.RESPONSES);
  }

  private static OpenAiUserChatClients clients(final OpenAiUserChatClients.Api api) {
    final var appOptions =
        OpenAiChatOptions.builder()
            .baseUrl("https://app/v1")
            .apiKey("app-key")
            .model("app-model")
            .maxRetries(0)
            .build();
    return new OpenAiUserChatClients(
        api,
        new UserModelRegistry(mock(UserModelConfigRepo.class), new AesGcmSealer(KEY, "t"), 3),
        appOptions,
        ToolCallingManager.builder().build(),
        List.of(),
        10);
  }
}
