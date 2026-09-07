package me.kezhenxu94.springagent.core.tools;

import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Generates images with whichever {@link ImageModel} the deployment's provider published, and saves
 * them under the user's artifacts directory, returning {@code file://} URLs for them. Where an
 * image goes from there — uploaded to a chat, published as a link — is up to whichever integration
 * renders the answer, which the scheme tells it how to do.
 *
 * <p>Nothing here names a provider. What the model asked for that {@code ImageOptions} cannot carry
 * travels as {@link ImageGenerationMetadata}; read that class before adding a parameter to the
 * tool.
 *
 * <p>Both halves of an {@code ImageResponse} are handled, because providers differ on which they
 * fill: OpenAI answers with base64 by default and a URL on request, DashScope only ever with a URL.
 * Saving the bytes locally either way is what makes the answer the same shape for every surface.
 */
@Slf4j
@AgentTool
@RequiredArgsConstructor
public class ImageGenerationTools {
  private final RestTemplate restTemplate;
  private final ImageModel imageModel;
  private final UserWorkspaceFactory userWorkspaceFactory;

  @Tool(
      name = "GenerateImage",
      description =
          "Generate an image from a prompt and return its URL, a file:// one naming where the image"
              + " was saved on this machine, to be shown with markdown as"
              + " ![description](file:///absolute/path.png). Also generates from reference images"
              + " where the configured image provider supports them: a local file has to be"
              + " published first with PublishFile (visibility=public, ttl=30m) and the URL it"
              + " returns passed as referenceImages.")
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
              description =
                  "Image size, passed to the image provider as written. Aspect ratios '1:1',"
                      + " '16:9' and '9:16' are understood by every provider; '2K' and '4K' and an"
                      + " explicit '1024x1024' are understood by some. A size the provider does not"
                      + " recognise means its own default, not a failure",
              required = false)
          final String size,
      @ToolParam(
              description =
                  "Whether to think before generating; false by default, and only honoured by a"
                      + " provider whose image model reasons",
              required = false)
          final Boolean thinkingMode,
      final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info(
        "Generating image for user {}, prompt: {}, referenceImages: {}",
        userId,
        prompt,
        referenceImages);

    final var references = new ArrayList<Media>();
    if (referenceImages != null) {
      for (final var imgUrl : referenceImages) {
        references.add(
            Media.builder()
                .name(imgUrl)
                // The reference is a URL the provider fetches for itself, which is why nothing is
                // downloaded here; the mime type is what Media insists on rather than a claim
                // about what is at the other end.
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(URI.create(imgUrl))
                .build());
      }
    }

    final var metadata = new HashMap<String, Object>();
    if (size != null && !size.isBlank()) {
      metadata.put(ImageGenerationMetadata.SIZE, size);
    }
    if (Boolean.TRUE.equals(thinkingMode)) {
      metadata.put(ImageGenerationMetadata.THINKING_MODE, true);
    }

    final var response =
        imageModel.call(
            new ImagePrompt(
                List.of(new ImageMessage(prompt, null, references, metadata)),
                ImageOptionsBuilder.builder().n(1).build()));

    if (response == null || response.getResults() == null) {
      log.error("Empty response from the image model");
      return List.of();
    }

    final var home = userWorkspaceFactory.forRequest(context);
    return response.getResults().stream()
        .map(generation -> generation.getOutput())
        .filter(Objects::nonNull)
        .map(image -> save(image, home))
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Writes one generated image into the user's artifacts directory, returning a {@code file://} URL
   * for it, or {@code null} if it could not be fetched or written — one failure shouldn't cost the
   * caller the images that did come back. A URL rather than a bare path so that whoever renders the
   * answer can tell from the scheme alone what it is holding.
   */
  private String save(final org.springframework.ai.image.Image image, final HomeDir home) {
    try {
      final byte[] imageBytes;
      if (image.getB64Json() != null && !image.getB64Json().isBlank()) {
        imageBytes = Base64.getDecoder().decode(image.getB64Json());
      } else if (image.getUrl() != null && !image.getUrl().isBlank()) {
        imageBytes = restTemplate.getForObject(URI.create(image.getUrl()), byte[].class);
      } else {
        log.error("Generated image carries neither a URL nor base64 content");
        return null;
      }
      if (imageBytes == null || imageBytes.length == 0) {
        log.error("Nothing to download at generated image URL: {}", image.getUrl());
        return null;
      }
      final var dest = home.artifacts().resolve("generated-" + UUID.randomUUID() + ".png");
      Files.write(dest, imageBytes);
      log.info("Saved generated image to {}, size={} bytes", dest, imageBytes.length);
      return dest.toUri().toString();
    } catch (Exception e) {
      log.error("Failed to save generated image: {}", image.getUrl(), e);
      return null;
    }
  }
}
