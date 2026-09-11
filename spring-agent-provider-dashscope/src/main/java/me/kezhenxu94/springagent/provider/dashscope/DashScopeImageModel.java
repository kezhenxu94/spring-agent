package me.kezhenxu94.springagent.provider.dashscope;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ImageGenerationMetadata;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

/**
 * DashScope's own image API, which is the one thing about this provider that is not OpenAI-shaped.
 *
 * <p>It is not {@code /v1/images/generations} with a prompt and a size. It is a multimodal
 * generation endpoint that takes a chat-shaped {@code input.messages} — text and reference images
 * interleaved as content parts — beside a {@code parameters} object, and answers with {@code
 * output.choices[].message.content[].image}. There is no way to express that through Spring AI's
 * {@code OpenAiImageOptions}, which is why this class exists rather than a configuration of Spring
 * AI's model.
 *
 * <p>Reference images have to be URLs the endpoint can fetch for itself. There is no upload path
 * here and deliberately none: DashScope would want a signed OSS object, which means credentials for
 * a bucket this project does not have. Core's {@code GenerateImage} says so in its own tool
 * description — a local file is published with {@code PublishFile (visibility=public, ttl=30m)}
 * first and the returned link passed in.
 *
 * <p>Answers carry a URL and never base64, and the URL expires within the hour. Core downloads it
 * into the user's artifacts directory immediately, which is what makes the answer outlive the link.
 */
@Slf4j
@RequiredArgsConstructor
public class DashScopeImageModel implements ImageModel {

  /** What DashScope generates at when the model asked for nothing in particular. */
  private static final String DEFAULT_SIZE = "2K";

  private final RestTemplate restTemplate;
  private final DashScopeProperties properties;

  @Override
  public ImageResponse call(final ImagePrompt request) {
    final var prompt =
        request.getInstructions().stream()
            .map(message -> message.getText())
            .filter(text -> text != null && !text.isBlank())
            .findFirst()
            .orElse("");

    // Reference images first and the text last, which is the order DashScope's examples use and the
    // order the model reads them in: the instruction applies to what came before it.
    final var content = new ArrayList<Map<String, String>>();
    for (final var message : request.getInstructions()) {
      for (final var media : message.getMedia()) {
        content.add(Map.of("image", referenceUrl(media)));
      }
    }
    content.add(Map.of("text", prompt));

    final var metadata =
        request.getInstructions().stream()
            .findFirst()
            .map(message -> message.getMetadata())
            .orElseGet(Map::of);

    final var parameters = new HashMap<String, Object>();
    final var size = metadata.get(ImageGenerationMetadata.SIZE);
    parameters.put("size", size instanceof String named && !named.isBlank() ? named : DEFAULT_SIZE);
    parameters.put("n", request.getOptions() == null ? 1 : orOne(request.getOptions().getN()));
    // Never watermarked. The images are the agent's answer to somebody's question, saved into their
    // own workspace and rendered inline in a chat; a vendor mark burnt into one is not information
    // the reader asked for.
    parameters.put("watermark", false);
    if (Boolean.TRUE.equals(metadata.get(ImageGenerationMetadata.THINKING_MODE))) {
      parameters.put("thinking_mode", true);
    }

    final var requestBody =
        Map.of(
            "model", properties.image().model(),
            "input", Map.of("messages", List.of(Map.of("role", "user", "content", content))),
            "parameters", parameters);

    final var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + properties.apiKey());
    headers.set("Content-Type", "application/json");

    final var response =
        restTemplate.postForObject(
            properties.imageGenerationUrl(),
            new HttpEntity<>(requestBody, headers),
            DashScopeImageResponse.class);

    if (response == null || response.output() == null || response.output().choices() == null) {
      log.error("Empty response from the DashScope image API");
      return new ImageResponse(List.of());
    }

    final var generations =
        response.output().choices().stream()
            .flatMap(choice -> choice.message().content().stream())
            .map(part -> part.image())
            .filter(url -> url != null && !url.isBlank())
            .map(url -> new ImageGeneration(new Image(url, null)))
            .toList();
    log.info("The DashScope image API returned {} image(s)", generations.size());
    return new ImageResponse(generations);
  }

  /**
   * The URL DashScope is to fetch a reference image from.
   *
   * <p>Core resolves a reference to bytes wherever it can — see {@code MediaSources}, and the
   * reason is that most providers want the bytes. This one is the exception: it has no upload path
   * and deliberately none, since it wants a signed OSS object and this project has no bucket. So
   * bytes are a request this endpoint cannot serve, and saying so is the whole job here.
   *
   * <p>Saying so <em>at all</em> is the point. This read {@code String.valueOf(media.getData())}
   * before, which for a {@code byte[]} produces {@code [B@1f2a3b4c}: a well-formed request carrying
   * a string that is not a URL, answered with a picture of nothing in particular or a 400 naming
   * neither the tool nor the file. A tool error naming {@code PublishFile} is something the model
   * can act on, and acting on it is a step it can take by itself.
   */
  private static String referenceUrl(final Media media) {
    final var data = media.getData();
    if (data instanceof URI || data instanceof String) {
      return data.toString();
    }
    log.warn(
        "Refusing a local reference image {} ({}): the DashScope image API fetches references"
            + " itself and has no upload path",
        media.getName(),
        data == null ? "null" : data.getClass().getSimpleName());
    throw new IllegalArgumentException(
        ("The configured image provider (DashScope) cannot read a local file as a reference image:"
                + " it fetches every reference itself. Publish %s first with PublishFile"
                + " (visibility=public, ttl=30m) and pass the URL it returns as the reference"
                + " instead.")
            .formatted(media.getName()));
  }

  private static int orOne(final Integer n) {
    return n == null || n < 1 ? 1 : n;
  }

  /**
   * The answer's shape. Public because {@code aot.DashScopeRuntimeHints} has to name these types —
   * {@code RestTemplate} fills them in reflectively, so a native image discards their constructors
   * unless told otherwise, and the symptom is an empty list rather than an error.
   */
  public record DashScopeImageResponse(Output output) {
    public record Output(List<Choice> choices) {}

    public record Choice(Message message) {}

    public record Message(String role, List<Content> content) {}

    public record Content(String image) {}
  }
}
