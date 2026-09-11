package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Answers questions about an image with the vision {@code ChatClient} the deployment's provider
 * published. Registered only where there is one — see {@code ModelToolsConfiguration}, which is
 * also why nothing here checks whether the endpoint is configured: a deployment that named no
 * vision model has no {@code RecognizeImage} tool at all, rather than one that refuses every call.
 */
@Slf4j
@AgentTool
@RequiredArgsConstructor
public class VisionTools {
  private final MediaSources mediaSources;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final CoreMessages messages;
  private final ChatClient visionChatClient;

  @Tool(
      name = "RecognizeImage",
      description =
          "Describe what an image shows, or answer a question about it. Takes local paths or"
              + " file:// URLs (only images already saved under the current user's, the current"
              + " group's, or the tenant's workspace/artifacts directory) or publicly reachable"
              + " http(s) URLs, and several images at once.")
  public String recognizeImage(
      @ToolParam(
              description =
                  "The images: absolute local paths, file:// URLs as returned by"
                      + " GenerateImage, or publicly reachable http(s) URLs")
          final List<String> images,
      @ToolParam(
              description = "What to ask about the images; omit it to just have them described",
              required = false)
          final String prompt,
      final ToolContext context) {
    if (images == null || images.isEmpty()) {
      log.warn("RecognizeImage called with no images");
      return messages.get("vision-no-image");
    }
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info(
        "Recognizing {} image(s) for user {}, prompt={}",
        images.size(),
        userId,
        Strings.isNullOrEmpty(prompt) ? "<default describe>" : prompt);
    log.debug("Images to recognize for user {}: {}", userId, images);

    final var home = userWorkspaceFactory.forRequest(context);
    final var mediaList =
        images.stream().map(src -> mediaSources.resolve(src, userId, home)).toList();
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
      return messages.get("vision-unreadable");
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

  private static long elapsedMillis(final long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
