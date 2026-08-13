package me.kezhenxu94.springagent.core.tools;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Generates images with DashScope and saves them under the user's artifacts directory, returning
 * {@code file://} URLs for them. Where an image goes from there — uploaded to a chat, published as
 * a link — is up to whichever integration renders the answer, which the scheme tells it how to do.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class ImageGenerationTools {
  private final RestTemplate restTemplate;
  private final SpringAgentProperties appConfiguration;
  private final UserWorkspaceFactory userWorkspaceFactory;

  @Tool(
      name = "GenerateImage",
      description =
          "Generate an image from a prompt and return its URL, a file:// one naming where the image"
              + " was saved on this machine, to be shown with markdown as"
              + " ![description](file:///absolute/path.png). Also generates from reference images:"
              + " a local file has to be published first with PublishFile (visibility=public,"
              + " ttl=30m) and the URL it returns passed as referenceImages.")
  public List<String> generateImage(
      @ToolParam(description = "The prompt describing the image") final String prompt,
      @ToolParam(
              description =
                  "Reference images to generate from. Each has to be a publicly reachable URL;"
                      + " publish a local file with PublishFile (visibility=public, ttl=30m) first"
                      + " and use the link it returns",
              required = false)
          final List<String> referenceImages,
      @ToolParam(
              description = "Image size: one of '2K', '4K', '1:1', '16:9', '9:16'",
              required = false)
          final String size,
      @ToolParam(
              description = "Whether to think before generating; false by default",
              required = false)
          final Boolean thinkingMode,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info(
        "Generating image for user {}, prompt: {}, referenceImages: {}",
        userId,
        prompt,
        referenceImages);

    final var content = new ArrayList<Map<String, String>>();
    if (referenceImages != null) {
      for (final var imgUrl : referenceImages) {
        content.add(Map.of("image", imgUrl));
      }
    }
    content.add(Map.of("text", prompt));

    final var parameters = new HashMap<String, Object>();
    parameters.put("size", size != null ? size : "2K");
    parameters.put("n", 1);
    parameters.put("watermark", false);
    if (Boolean.TRUE.equals(thinkingMode)) {
      parameters.put("thinking_mode", true);
    }

    final var requestBody =
        Map.of(
            "model", appConfiguration.dashscope().image().model(),
            "input", Map.of("messages", List.of(Map.of("role", "user", "content", content))),
            "parameters", parameters);

    final var headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + appConfiguration.dashscope().image().apiKey());
    headers.set("Content-Type", "application/json");

    final var response =
        restTemplate.postForObject(
            appConfiguration.dashscope().image().baseUrl(),
            new HttpEntity<>(requestBody, headers),
            DashScopeImageResponse.class);

    if (response == null || response.output() == null || response.output().choices() == null) {
      log.error("Empty response from DashScope image API");
      return List.of();
    }

    return response.output().choices().stream()
        .flatMap(
            choice ->
                choice.message().content().stream()
                    .filter(c -> c.image() != null)
                    .map(c -> c.image()))
        .map(imageUrl -> save(imageUrl, userId))
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Downloads a generated image into the user's artifacts directory, returning a {@code file://}
   * URL for it, or {@code null} if it could not be fetched or written — one failure shouldn't cost
   * the caller the images that did come back. A URL rather than a bare path so that whoever renders
   * the answer can tell from the scheme alone what it is holding.
   */
  private String save(final String imageUrl, final String userId) {
    try {
      final var imageBytes = restTemplate.getForObject(URI.create(imageUrl), byte[].class);
      if (imageBytes == null) {
        log.error("Nothing to download at generated image URL: {}", imageUrl);
        return null;
      }
      final var dest =
          userWorkspaceFactory
              .forOwner(userId)
              .artifacts()
              .resolve("generated-" + UUID.randomUUID() + ".png");
      Files.write(dest, imageBytes);
      log.info("Saved generated image to {}, size={} bytes", dest, imageBytes.length);
      return dest.toUri().toString();
    } catch (Exception e) {
      log.error("Failed to save generated image: {}", imageUrl, e);
      return null;
    }
  }

  record DashScopeImageResponse(Output output) {
    record Output(List<Choice> choices) {}

    record Choice(Message message) {}

    record Message(String role, List<Content> content) {}

    record Content(String image) {}
  }
}
