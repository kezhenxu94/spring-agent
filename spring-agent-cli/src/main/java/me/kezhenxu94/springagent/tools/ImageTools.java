package me.kezhenxu94.springagent.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.bot.configuration.SpringAgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class ImageTools {
  private final Client feishu;
  private final RestTemplate restTemplate;
  private final SpringAgentProperties appConfiguration;
  private final UserWorkspaceFactory userWorkspaceFactory;

  @Qualifier("vision")
  private final ChatClient visionChatClient;

  @Tool(
      name = "GenerateImage",
      description =
          "根据提示词生成图片, 返回图片的 URL, 使用 markdown 显示图片; 支持图生图, "
              + "若参考图片是本地文件而非可公开访问的 URL, 请先调用 PublishFileTool 的 publishFile "
              + "(visibility=public, ttl=30m) 发布该文件获取公开 URL, 再传入 referenceImages")
  public List<String> generateImage(
      @ToolParam(description = "提示词, Prompt") final String prompt,
      @ToolParam(
              description =
                  "参考图片 URL 列表, 用于图生图; 必须是可公开访问的 URL, 本地文件请先用 PublishFileTool 的 publishFile"
                      + " (visibility=public, ttl=30m) 发布后再使用其返回的链接",
              required = false)
          final List<String> referenceImages,
      @ToolParam(description = "图片尺寸, 可选值: '2K', '4K', '1:1', '16:9', '9:16'", required = false)
          final String size,
      @ToolParam(description = "是否开启思考模式, 默认 false", required = false) final Boolean thinkingMode) {
    log.info("Generating image for prompt: {}, referenceImages: {}", prompt, referenceImages);

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
        .map(
            imageUrl -> {
              try {
                final var imageBytes =
                    restTemplate.getForObject(URI.create(imageUrl), byte[].class);
                final var tempFile = File.createTempFile("image-", ".png");
                try (final var fos = new FileOutputStream(tempFile)) {
                  fos.write(imageBytes);
                }
                final var resp =
                    feishu
                        .im()
                        .v1()
                        .image()
                        .create(
                            CreateImageReq.newBuilder()
                                .createImageReqBody(
                                    CreateImageReqBody.newBuilder()
                                        .imageType("message")
                                        .image(tempFile)
                                        .build())
                                .build());
                tempFile.delete();
                return resp.getData().getImageKey();
              } catch (Exception e) {
                log.error("Failed to process image", e);
                return null;
              }
            })
        .filter(key -> key != null)
        .map(it -> "https://image-key/" + it)
        .collect(Collectors.toList());
  }

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

  record DashScopeImageResponse(Output output) {
    record Output(List<Choice> choices) {}

    record Choice(Message message) {}

    record Message(String role, List<Content> content) {}

    record Content(String image) {}
  }
}
