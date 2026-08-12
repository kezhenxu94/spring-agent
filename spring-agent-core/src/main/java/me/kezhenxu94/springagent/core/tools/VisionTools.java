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
          "识别/描述图片内容或回答关于图片的问题 (视觉理解), 支持本地文件路径 (必须是此前保存到当前用户 workspace/artifacts "
              + "目录内的图片) 或可公开访问的图片 URL, 可同时传入多张图片")
  public String recognizeImage(
      @ToolParam(description = "图片来源列表: 本地文件绝对路径或可公开访问的图片 URL") final List<String> images,
      @ToolParam(description = "关于图片的问题或指令, 不填则默认让模型描述图片内容", required = false) final String prompt,
      final ToolContext context) {
    if (images == null || images.isEmpty()) {
      return "错误：请至少提供一张图片。";
    }
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    log.info("Recognizing {} image(s) for user {}", images.size(), userId);

    final var mediaList = images.stream().map(src -> resolveMedia(src, userId)).toList();
    if (mediaList.stream().anyMatch(Objects::isNull)) {
      return "错误：无法读取图片，请检查路径或 URL 是否正确，本地路径必须在当前用户的 workspace 目录内。";
    }

    return visionChatClient
        .prompt()
        .user(
            u -> {
              u.text(Strings.isNullOrEmpty(prompt) ? "请描述这张图片的内容。" : prompt);
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
