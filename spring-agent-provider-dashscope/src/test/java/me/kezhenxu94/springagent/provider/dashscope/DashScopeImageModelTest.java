package me.kezhenxu94.springagent.provider.dashscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one API in this project that is nobody's standard, pinned from both ends: the envelope that
 * goes out and the shape that comes back.
 *
 * <p>Worth a test of its own rather than trusting the types, because neither end is checked by a
 * compiler. The request is a {@code Map} assembled by hand, so a renamed key fails as a rejection
 * from Alibaba rather than at the build; the response is parsed into records whose names have to
 * match a JSON document written elsewhere, and a mismatch there is an empty list rather than an
 * error — {@code GenerateImage} would report generating no images and nobody would know why.
 */
class DashScopeImageModelTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private MockWebServer server;
  private DashScopeImageModel model;

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.start();
    model =
        new DashScopeImageModel(
            new RestTemplate(),
            new DashScopeProperties(
                "sk-dashscope",
                // A host, as the property means: the generation path is appended by the module.
                server.url("").toString(),
                null,
                null,
                new DashScopeProperties.Image("wan2.7-image-pro", null),
                null));
  }

  @AfterEach
  void stop() throws Exception {
    server.shutdown();
  }

  private void answerWith(final String body) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body));
  }

  @Test
  @DisplayName("the prompt goes out in DashScope's own envelope")
  void theRequestEnvelope() throws Exception {
    answerWith(
        "{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\","
            + "\"content\":[{\"image\":\"https://example.test/a.png\"}]}}]}}");

    model.call(
        new ImagePrompt(
            List.of(new ImageMessage("a cat", null, List.of(), Map.of())),
            ImageOptionsBuilder.builder().n(1).build()));

    final var request = server.takeRequest();
    // Derived, not configured: a deployment names the host and this is the path it gets.
    assertThat(request.getPath()).isEqualTo(DashScopeProperties.IMAGE_GENERATION_PATH);
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer sk-dashscope");

    final var body = JSON.readTree(request.getBody().readUtf8());
    assertThat(body.get("model").asString()).isEqualTo("wan2.7-image-pro");
    // input.messages[0].content, not a flat prompt: this endpoint is chat-shaped, which is what
    // makes room for the reference images below.
    final var content = body.get("input").get("messages").get(0).get("content");
    assertThat(content.get(0).get("text").asString()).isEqualTo("a cat");
    final var parameters = body.get("parameters");
    assertThat(parameters.get("size").asString()).isEqualTo("2K");
    assertThat(parameters.get("n").asInt()).isEqualTo(1);
    assertThat(parameters.get("watermark").asBoolean()).isFalse();
    // Absent rather than false: the endpoint reads the field's presence.
    assertThat(parameters.has("thinking_mode")).isFalse();
  }

  @Test
  @DisplayName("reference images go first and the instruction last")
  void referenceImagesComeFirst() throws Exception {
    answerWith("{\"output\":{\"choices\":[]}}");

    model.call(
        new ImagePrompt(
            List.of(
                new ImageMessage(
                    "as a watercolour",
                    null,
                    List.of(
                        Media.builder()
                            .name("ref")
                            .mimeType(MimeTypeUtils.IMAGE_PNG)
                            .data(URI.create("https://example.test/ref.png"))
                            .build()),
                    Map.of())),
            ImageOptionsBuilder.builder().n(1).build()));

    final var content =
        JSON.readTree(server.takeRequest().getBody().readUtf8())
            .get("input")
            .get("messages")
            .get(0)
            .get("content");
    // The order is the order the model reads them in: the instruction applies to what came before.
    assertThat(content.get(0).get("image").asString()).isEqualTo("https://example.test/ref.png");
    assertThat(content.get(1).get("text").asString()).isEqualTo("as a watercolour");
  }

  @Test
  @DisplayName("a local reference is refused by name, never sent as the string [B@...")
  void bytesAreRefusedRatherThanSent() {
    // Core resolves a local path or file:// URL into bytes before a provider sees it, because that
    // is what every other provider wants. This endpoint is the exception — it fetches references
    // itself and has no upload path — and what matters is that it says so.
    //
    // It read String.valueOf(media.getData()) before, which for a byte[] yields "[B@1f2a3b4c": a
    // well-formed request carrying a string that is not a URL, answered with a picture of nothing
    // in particular. The model could not act on that; it can act on this.
    final var prompt =
        new ImagePrompt(
            List.of(
                new ImageMessage(
                    "as a watercolour",
                    null,
                    List.of(
                        Media.builder()
                            .name("generated-1.png")
                            .mimeType(MimeTypeUtils.IMAGE_PNG)
                            .data(
                                "pretend-png-bytes"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .build()),
                    Map.of())),
            ImageOptionsBuilder.builder().n(1).build());

    assertThatThrownBy(() -> model.call(prompt))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PublishFile")
        .hasMessageContaining("generated-1.png");

    // And nothing was put on the wire, so there is no half-made request to explain.
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  @DisplayName("what the tool asked for in metadata reaches the parameters")
  void metadataReachesTheParameters() throws Exception {
    answerWith("{\"output\":{\"choices\":[]}}");

    model.call(
        new ImagePrompt(
            List.of(
                new ImageMessage(
                    "a cat",
                    null,
                    List.of(),
                    Map.of(
                        ImageGenerationMetadata.SIZE,
                        "16:9",
                        ImageGenerationMetadata.THINKING_MODE,
                        true))),
            ImageOptionsBuilder.builder().n(1).build()));

    final var parameters =
        JSON.readTree(server.takeRequest().getBody().readUtf8()).get("parameters");
    // Passed through as written: DashScope's own vocabulary, which is why core does not parse it.
    assertThat(parameters.get("size").asString()).isEqualTo("16:9");
    assertThat(parameters.get("thinking_mode").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("the answer's images are read out of output.choices[].message.content[].image")
  void theResponseShape() {
    answerWith(
        "{\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":["
            + "{\"image\":\"https://example.test/a.png\"},{\"image\":\"https://example.test/b.png\"}]}}]}}");

    final var response = model.call(new ImagePrompt("a cat"));

    assertThat(response.getResults()).hasSize(2);
    assertThat(response.getResults().get(0).getOutput().getUrl())
        .isEqualTo("https://example.test/a.png");
    // A URL and never base64, which is why core downloads it: the link expires within the hour.
    assertThat(response.getResults().get(0).getOutput().getB64Json()).isNull();
  }

  @Test
  @DisplayName("an answer that carries no images is an empty response, not a failure")
  void anEmptyAnswer() {
    answerWith("{}");

    assertThat(model.call(new ImagePrompt("a cat")).getResults()).isEmpty();
  }
}
