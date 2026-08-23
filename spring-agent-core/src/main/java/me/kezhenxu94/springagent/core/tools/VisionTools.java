package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class VisionTools {
  private final RestTemplate restTemplate;
  private final UserWorkspaceFactory userWorkspaceFactory;

  @Qualifier("vision")
  private final ChatClient visionChatClient;

  @Tool(
      name = "RecognizeImage",
      description =
          "Describe what an image shows, or answer a question about it. Takes local paths (only"
              + " images already saved under the current user's, the current group's, or the"
              + " tenant's workspace/artifacts directory) or publicly reachable URLs, and several"
              + " images at once.")
  public String recognizeImage(
      @ToolParam(description = "The images: absolute local paths, or publicly reachable URLs")
          final List<String> images,
      @ToolParam(
              description = "What to ask about the images; omit it to just have them described",
              required = false)
          final String prompt,
      final ToolContext context) {
    if (images == null || images.isEmpty()) {
      log.warn("RecognizeImage called with no images");
      return "Error: give at least one image.";
    }
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info(
        "Recognizing {} image(s) for user {}, prompt={}",
        images.size(),
        userId,
        Strings.isNullOrEmpty(prompt) ? "<default describe>" : prompt);
    log.debug("Images to recognize for user {}: {}", userId, images);

    final var home = userWorkspaceFactory.forRequest(context);
    final var mediaList = images.stream().map(src -> resolveMedia(src, userId, home)).toList();
    if (mediaList.stream().anyMatch(Objects::isNull)) {
      final var failed =
          IntStream.range(0, mediaList.size())
              .filter(i -> mediaList.get(i) == null)
              .mapToObj(images::get)
              .toList();
      log.warn(
          "Giving up on RecognizeImage for user {}: {} of {} image(s) unreadable: {}",
          userId,
          failed.size(),
          images.size(),
          failed);
      return "Error: none of the images could be read. Check the paths and URLs; a local path has"
          + " to be inside the current user's, group's, or tenant's workspace directory.";
    }

    final var startedAt = System.nanoTime();
    try {
      final var content =
          visionChatClient
              .prompt()
              .user(
                  u -> {
                    final var text =
                        Strings.isNullOrEmpty(prompt) ? "Describe what this image shows." : prompt;
                    log.info(
                        "Calling vision model for user {} with text={}, media={}",
                        userId,
                        text,
                        mediaList.stream()
                            .map(m -> m.getName() + " (" + m.getMimeType() + ")")
                            .toList());
                    u.text(text);
                    mediaList.forEach(u::media);
                  })
              .call()
              .content();
      log.info(
          "Recognized {} image(s) for user {} in {} ms, result length={}",
          mediaList.size(),
          userId,
          elapsedMillis(startedAt),
          content == null ? 0 : content.length());
      log.debug("RecognizeImage result for user {}: {}", userId, content);
      return content;
    } catch (RuntimeException e) {
      log.error(
          "Vision model call failed for user {} after {} ms with {} image(s)",
          userId,
          elapsedMillis(startedAt),
          mediaList.size(),
          e);
      throw e;
    }
  }

  private Media resolveMedia(final String source, final String userId, final HomeDir home) {
    final var startedAt = System.nanoTime();
    try {
      if (source.startsWith("http://") || source.startsWith("https://")) {
        log.debug("Downloading image from URL: {}", source);
        final var bytes = restTemplate.getForObject(URI.create(source), byte[].class);
        if (bytes == null || bytes.length == 0) {
          log.warn("Nothing to read at image URL: {}", source);
          return null;
        }
        log.info(
            "Downloaded image {}, size={} bytes, took {} ms",
            source,
            bytes.length,
            elapsedMillis(startedAt));
        return Media.builder().name(source).mimeType(MimeTypeUtils.IMAGE_PNG).data(bytes).build();
      }
      final var path = Path.of(source).toAbsolutePath().normalize();
      if (!home.contains(path) || !Files.exists(path)) {
        log.warn(
            "Rejected image path outside user {} workspace or missing: {} (resolved to {})",
            userId,
            source,
            path);
        return null;
      }
      final var mimeType = Files.probeContentType(path);
      if (mimeType == null) {
        log.info("Could not probe mime type of {}, assuming {}", path, MimeTypeUtils.IMAGE_PNG);
      }
      final var bytes = Files.readAllBytes(path);
      log.info(
          "Read local image {}, mimeType={}, size={} bytes, took {} ms",
          path,
          mimeType != null ? mimeType : MimeTypeUtils.IMAGE_PNG_VALUE,
          bytes.length,
          elapsedMillis(startedAt));
      return Media.builder()
          .name(path.getFileName().toString())
          .mimeType(
              mimeType != null ? MimeTypeUtils.parseMimeType(mimeType) : MimeTypeUtils.IMAGE_PNG)
          .data(bytes)
          .build();
    } catch (Exception e) {
      log.error("Failed to load image: {} (after {} ms)", source, elapsedMillis(startedAt), e);
      return null;
    }
  }

  private static long elapsedMillis(final long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
