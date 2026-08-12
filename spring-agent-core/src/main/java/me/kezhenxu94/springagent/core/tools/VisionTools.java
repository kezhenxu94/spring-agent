package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
              + " images already saved under the current user's workspace/artifacts directory) or"
              + " publicly reachable URLs, and several images at once.")
  public String recognizeImage(
      @ToolParam(description = "The images: absolute local paths, or publicly reachable URLs")
          final List<String> images,
      @ToolParam(
              description = "What to ask about the images; omit it to just have them described",
              required = false)
          final String prompt,
      final ToolContext context) {
    if (images == null || images.isEmpty()) {
      return "Error: give at least one image.";
    }
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info("Recognizing {} image(s) for user {}", images.size(), userId);

    final var mediaList = images.stream().map(src -> resolveMedia(src, userId)).toList();
    if (mediaList.stream().anyMatch(Objects::isNull)) {
      return "Error: none of the images could be read. Check the paths and URLs; a local path has"
          + " to be inside the current user's workspace directory.";
    }

    return visionChatClient
        .prompt()
        .user(
            u -> {
              u.text(Strings.isNullOrEmpty(prompt) ? "Describe what this image shows." : prompt);
              mediaList.forEach(u::media);
            })
        .call()
        .content();
  }

  private Media resolveMedia(final String source, final String userId) {
    try {
      if (source.startsWith("http://") || source.startsWith("https://")) {
        final var bytes = restTemplate.getForObject(URI.create(source), byte[].class);
        return Media.builder().name(source).mimeType(MimeTypeUtils.IMAGE_PNG).data(bytes).build();
      }
      final var path = Path.of(source).toAbsolutePath().normalize();
      if (!userWorkspaceFactory.forOwner(userId).contains(path) || !Files.exists(path)) {
        log.warn("Rejected image path outside user workspace or missing: {}", source);
        return null;
      }
      final var mimeType = Files.probeContentType(path);
      return Media.builder()
          .name(path.getFileName().toString())
          .mimeType(
              mimeType != null ? MimeTypeUtils.parseMimeType(mimeType) : MimeTypeUtils.IMAGE_PNG)
          .data(Files.readAllBytes(path))
          .build();
    } catch (Exception e) {
      log.error("Failed to load image: {}", source, e);
      return null;
    }
  }
}
