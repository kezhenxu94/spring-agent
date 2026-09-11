package me.kezhenxu94.springagent.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import me.kezhenxu94.springagent.core.tools.i18n.DescribingToolCallingManager;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * What a run on a user's own endpoint actually offers the model, read off the wire.
 *
 * <p>The tool list a request carries is built by the <b>chat model</b>, from its own {@code
 * ToolCallingManager#resolveToolDefinitions} — the advisor only executes the calls that come back.
 * So a client built by hand is where the runtime's rewrites of the definition list go missing, and
 * nothing about the run looks broken when they do: the model answers, the tools work, and only the
 * titles on a card and the language of the descriptions are gone. Nothing but the bytes sent to the
 * endpoint can tell, which is why this test reads them.
 */
class OpenAiUserModelToolsTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  /** Enough of a completion for the SDK to parse; nothing here cares what the model said. */
  private static final String ANSWER =
      """
      {"id":"1","object":"chat.completion","created":1,"model":"own-model",\
      "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},\
      "finish_reason":"stop"}]}\
      """;

  private MockWebServer server;

  @BeforeEach
  void startServer() throws Exception {
    this.server = new MockWebServer();
    this.server.start();
  }

  @AfterEach
  void stopServer() throws Exception {
    this.server.shutdown();
  }

  @Test
  @DisplayName("a run on a user's own endpoint is offered the display parameter, like every other")
  void offersTheDisplayParameter() throws Exception {
    final var request = requestFor(clientsWith(runtimeManager()));

    assertThat(request)
        .as("the tool reached the endpoint at all")
        .contains("\"name\":\"forecast\"")
        .contains("\"city\"");
    assertThat(request)
        .as(
            "without this a user on a model of their own gets a card of untitled tool calls, and"
                + " every tool description in English whatever the workspace asked for")
        .contains(DisplayDescription.FIELD);
  }

  @Test
  @DisplayName("and it is the runtime's manager that puts it there, not the model's default")
  void withoutTheRuntimesManagerItIsAbsent() throws Exception {
    // The failure this guards against, stated as the difference it makes: OpenAiChatModel.Builder
    // substitutes a plain manager for one nobody set, and a plain manager offers the tool exactly
    // as its author declared it.
    final var request = requestFor(clientsWith(ToolCallingManager.builder().build()));

    assertThat(request).contains("\"name\":\"forecast\"").doesNotContain(DisplayDescription.FIELD);
  }

  /** The body of the chat completion a run through {@code clients} sent to the endpoint. */
  private String requestFor(final OpenAiUserChatClients clients) throws Exception {
    this.server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(ANSWER));

    // probeClient rather than clientFor, only so that the token needs no sealing: both build the
    // client the same way, through the same cache.
    final var client =
        clients.probeClient(
            UserModelConfig.builder()
                .name("mine")
                .baseUrl(this.server.url("/v1").toString())
                .model("own-model")
                .build(),
            "own-token");

    client.prompt().user("what is the weather").tools(new Forecast()).call().content();

    return this.server.takeRequest().getBody().readUtf8();
  }

  private OpenAiUserChatClients clientsWith(final ToolCallingManager manager) {
    final var appOptions =
        OpenAiChatOptions.builder()
            .baseUrl("https://app/v1")
            .apiKey("app-key")
            .model("app-model")
            // Nothing here is meant to be retried: a stub that answers once would otherwise be
            // asked again by the SDK before the failure surfaced.
            .maxRetries(0)
            .build();
    return new OpenAiUserChatClients(
        new UserModelRegistry(mock(UserModelConfigRepo.class), new AesGcmSealer(KEY, "t"), 3),
        appOptions,
        manager,
        List.of(),
        10);
  }

  /** The manager core builds, as far as the definition list is concerned. */
  private static ToolCallingManager runtimeManager() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new DescribingToolCallingManager(
        ToolCallingManager.builder().build(),
        new CoreMessages(source, new SpringAgentProperties(null, Locale.ENGLISH, null, null)));
  }

  /** Something to offer the model, with a parameter of its own so both can be seen on the wire. */
  static class Forecast {

    @Tool(description = "The forecast for a city")
    String forecast(final String city) {
      return "sunny in " + city;
    }
  }
}
