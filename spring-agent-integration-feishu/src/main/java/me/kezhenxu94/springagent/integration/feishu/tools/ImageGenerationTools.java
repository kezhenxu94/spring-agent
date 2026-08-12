package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Generates images with DashScope and uploads them to Feishu, returning {@code
 * https://image-key/<key>} URLs — a private contract with {@code FeishuCardUpdater}, which resolves
 * the keys when rendering a card. That contract is why this lives here rather than in core beside
 * {@code VisionTools}.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class ImageGenerationTools {
  private final Client feishu;
  private final RestTemplate restTemplate;
  private final SpringAgentProperties appConfiguration;

  @Tool(
      name = "GenerateImage",
      description =
          "Generate an image from a prompt and return its URL, to be shown with markdown. Also"
              + " generates from reference images: a local file has to be published first with"
              + " PublishFile (visibility=public, ttl=30m) and the URL it returns passed as"
              + " referenceImages.")
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
          final Boolean thinkingMode) {
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

  record DashScopeImageResponse(Output output) {
    record Output(List<Choice> choices) {}

    record Choice(Message message) {}

    record Message(String role, List<Content> content) {}

    record Content(String image) {}
  }
}
