package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.openai.models.completions.CompletionUsage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLConnection;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class FeishuCardUpdater implements AgentResponseListener, TodoEventHandler {
  public static final ToolContextKey<FeishuCardUpdater> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("FeishuCardUpdater", FeishuCardUpdater.class);

  private static final int CODE_STREAMING_MODE_CLOSED = 300309;

  private final Client feishu;
  private final JsonMapper om;
  private final String cardId;
  private final RestTemplate restTemplate;
  private final UnaryOperator<String> contentTransformer;
  private final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing;
  private final Instant startedAt = Instant.now();
  private final AtomicInteger sequence = new AtomicInteger(2);
  private String lastBaseContent = "";

  public FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing) {
    this(feishu, om, cardId, null, UnaryOperator.identity(), modelPricing);
  }

  public FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final UnaryOperator<String> contentTransformer,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing) {
    this(feishu, om, cardId, null, contentTransformer, modelPricing);
  }

  public FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final RestTemplate restTemplate,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing) {
    this(feishu, om, cardId, restTemplate, null, modelPricing);
  }

  private FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final RestTemplate restTemplate,
      final UnaryOperator<String> contentTransformer,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing) {
    this.feishu = feishu;
    this.om = om;
    this.cardId = cardId;
    this.restTemplate = restTemplate;
    this.contentTransformer =
        contentTransformer != null ? contentTransformer : this::reuploadImages;
    this.modelPricing = modelPricing != null ? modelPricing : Map.of();
  }

  private static boolean isThinkingMode(Usage usage) {
    if (usage == null || !(usage.getNativeUsage() instanceof CompletionUsage openAiUsage)) {
      return false;
    }
    final var details = openAiUsage.completionTokensDetails().orElse(null);
    return details != null
        && details.reasoningTokens().isPresent()
        && details.reasoningTokens().get() > 0;
  }

  private String approxCost(String model, Usage usage) {
    final var pricing = modelPricing.get(model);
    if (pricing == null
        || usage == null
        || usage.getPromptTokens() == null
        || usage.getCompletionTokens() == null) {
      return null;
    }
    final var inputPrice =
        isThinkingMode(usage)
            ? pricing.thinkingInputPerMillion()
            : pricing.nonThinkingInputPerMillion();
    final var cost =
        (usage.getPromptTokens() * inputPrice
                + usage.getCompletionTokens() * pricing.outputPerMillion())
            / 1_000_000.0;
    return String.format(Locale.ROOT, "~%s%.2f", pricing.currency().symbol(), cost);
  }

  private static String formatElapsed(Duration elapsed) {
    final var seconds = elapsed.getSeconds();
    return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
  }

  public String getCardId() {
    return cardId;
  }

  public synchronized void updateContent(String content) {
    log.debug(
        "updateContent: cardId={}, length={}", cardId, content != null ? content.length() : 0);
    this.lastBaseContent = content;
    sendContent(content);
  }

  private synchronized void showError(Throwable error) {
    final var summary = errorDisplay(error);
    log.warn("showError: cardId={}, error={}", cardId, summary);
    final var quoted =
        Arrays.stream(Throwables.getStackTraceAsString(error).split("\n"))
            .map(line -> "> " + line)
            .collect(Collectors.joining("\n"));
    updateContent("出错了: " + summary + "\n\n" + quoted);
  }

  private static String errorDisplay(Throwable error) {
    if (error == null) return "未知错误";
    final var msg = error.getMessage();
    return Strings.isNullOrEmpty(msg) ? error.getClass().getSimpleName() : msg;
  }

  public synchronized void setToolStatus(
      String toolName, String toolInput, ToolContext toolContext) {
    final var quotedInput = quoteToolInput(toolInput);
    log.info("Tool call: cardId={}, tool={}", cardId, toolName);
    sendContent(lastBaseContent + "\n正在调用 " + toolName + " ..." + quotedInput);
  }

  private String quoteToolInput(String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return "";
    }
    final var quoted =
        Arrays.stream(formatToolInput(toolInput).split("\n"))
            .map(line -> "> " + line)
            .collect(Collectors.joining("\n"));
    return "\n" + quoted;
  }

  private String formatToolInput(String toolInput) {
    try {
      final var node = om.readTree(toolInput);
      if (!node.isObject()) {
        return toolInput;
      }
      final var fields =
          node.properties().stream()
              .map(entry -> entry.getKey() + ": " + entry.getValue().asString())
              .collect(Collectors.toList());
      return String.join("\n", fields);
    } catch (JacksonException e) {
      return toolInput;
    }
  }

  private synchronized void sendContent(String content) {
    sendElementContent("message", content);
  }

  private String formatTodoItem(TodoWriteTool.Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> "- [✔] " + item.content();
      case in_progress -> "- [*] **" + item.activeForm() + "**";
      case pending -> "- [ ] " + item.content();
    };
  }

  @SneakyThrows
  private synchronized void sendElementContent(String elementId, String content) {
    sendElementContent(elementId, content, true);
  }

  @SneakyThrows
  private synchronized void sendElementContent(
      String elementId, String content, boolean allowRetry) {
    final var seq = sequence.getAndIncrement();
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .content(
                ContentCardElementReq.newBuilder()
                    .cardId(cardId)
                    .elementId(elementId)
                    .contentCardElementReqBody(
                        ContentCardElementReqBody.newBuilder()
                            .sequence(seq)
                            .content(content)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to send {} content: cardId={}, seq={}, code={}, msg={}",
          elementId,
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
      if (allowRetry && response.getCode() == CODE_STREAMING_MODE_CLOSED) {
        log.info("Streaming mode closed for cardId={}, re-enabling and retrying", cardId);
        reenableStreaming();
        sendElementContent(elementId, content, false);
      }
    }
  }

  @SneakyThrows
  private void reenableStreaming() {
    final var response =
        feishu
            .cardkit()
            .v1()
            .card()
            .settings(
                SettingsCardReq.newBuilder()
                    .cardId(cardId)
                    .settingsCardReqBody(
                        SettingsCardReqBody.newBuilder()
                            .sequence(sequence.getAndIncrement())
                            .settings(
                                """
                                {
                                  "schema": "2.0",
                                  "config": {
                                      "update_multi": true,
                                      "streaming_mode": true,
                                      "streaming_config": {
                                          "print_step": {"default": 1},
                                          "print_frequency_ms": {"default": 70},
                                          "print_strategy": "fast"
                                      }
                                  }
                                }
                                """)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.error(
          "Failed to re-enable streaming mode: cardId={}, code={}, msg={}",
          cardId,
          response.getCode(),
          response.getMsg());
    } else {
      log.info("Re-enabled streaming mode: cardId={}", cardId);
    }
  }

  public synchronized void updateUsageFooter(String model, Usage usage) {
    if (Strings.isNullOrEmpty(model)) {
      log.debug("updateUsageFooter: skipped, model is empty for cardId={}", cardId);
      return;
    }
    final var cost = approxCost(model, usage);
    final var usageText =
        usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
            ? String.format(
                "%s · ↑%d ↓%d%s · %s",
                model,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                cost != null ? " · " + cost : "",
                formatElapsed(Duration.between(startedAt, Instant.now())))
            : model;
    log.debug(
        "updateUsageFooter: cardId={}, model={}, promptTokens={}, completionTokens={}",
        cardId,
        model,
        usage != null ? usage.getPromptTokens() : null,
        usage != null ? usage.getCompletionTokens() : null);
    sendElementContent("usage", usageText);
  }

  @SneakyThrows
  public synchronized void finalizeCard() {
    log.info("Finalizing card: cardId={}", cardId);

    final var removeActionsResponse =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .delete(
                DeleteCardElementReq.newBuilder()
                    .cardId(cardId)
                    .elementId("stop")
                    .deleteCardElementReqBody(
                        DeleteCardElementReqBody.newBuilder()
                            .sequence(sequence.getAndIncrement())
                            .build())
                    .build());
    if (removeActionsResponse.getCode() != 0) {
      log.error(
          "Failed to remove stop button: cardId={}, code={}, msg={}",
          cardId,
          removeActionsResponse.getCode(),
          removeActionsResponse.getMsg());
    }
    final var stopResponse =
        feishu
            .cardkit()
            .v1()
            .card()
            .settings(
                SettingsCardReq.newBuilder()
                    .cardId(cardId)
                    .settingsCardReqBody(
                        SettingsCardReqBody.newBuilder()
                            .sequence(sequence.getAndIncrement())
                            .settings(
                                """
                                {
                                  "schema": "2.0",
                                  "config": {
                                      "update_multi": true,
                                      "streaming_mode": false
                                  }
                                }
                                """)
                            .build())
                    .build());
    if (stopResponse.getCode() != 0) {
      log.error(
          "Failed to stop card streaming mode: cardId={}, code={}, msg={}",
          cardId,
          stopResponse.getCode(),
          stopResponse.getMsg());
    } else {
      log.info("Card finalized: cardId={}", cardId);
    }
  }

  @Override
  public void onModel(String model) {
    updateUsageFooter(model, null);
  }

  @Override
  public void onContent(String contentSoFar) {
    updateContent(contentTransformer.apply(contentSoFar));
  }

  @Override
  public void onUsage(String model, Usage usage) {
    updateUsageFooter(model, usage);
  }

  @Override
  public void onError(Throwable error) {
    showError(error);
  }

  @Override
  public void onFinished(AgentOutcome outcome) {
    finalizeCard();
  }

  private String reuploadImages(String content) {
    if (Strings.isNullOrEmpty(content)) {
      return content;
    }
    final var buffer = new StringBuffer(content);

    final var imageKeyPattern = Pattern.compile("!\\[(.*?)\\]\\(https://image-key/(.*?)\\)");
    final var imageKeyMatcher = imageKeyPattern.matcher(buffer);
    while (imageKeyMatcher.find()) {
      final var imageName = imageKeyMatcher.group(1);
      final var imageKey = imageKeyMatcher.group(2);
      buffer.replace(
          imageKeyMatcher.start(), imageKeyMatcher.end(), "![" + imageName + "](" + imageKey + ")");
    }

    final var imagePattern = Pattern.compile("!\\[(.*?)\\]\\((https?://.*?)\\)");
    final var matcher = imagePattern.matcher(buffer);
    while (matcher.find()) {
      final var imageName = matcher.group(1);
      final var imageUrl = matcher.group(2);
      try {
        final var imageBytes = restTemplate.getForObject(imageUrl, byte[].class);
        if (imageBytes == null) {
          continue;
        }

        final var extension =
            URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(imageBytes))
                .split("/")[1];
        final var tempFile = File.createTempFile(imageName, "." + extension);
        Files.write(tempFile.toPath(), imageBytes);

        final var uploadResponse =
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

        if (uploadResponse.getCode() == 0) {
          buffer.replace(
              matcher.start(),
              matcher.end(),
              "![" + imageName + "](" + uploadResponse.getData().getImageKey() + ")");
        } else {
          log.warn("Failed to upload image: {}, {}, {}", imageName, imageUrl, uploadResponse);
          buffer.replace(matcher.start(), matcher.end(), "(图片无法显示)");
        }
        tempFile.delete();
      } catch (Exception e) {
        log.error("Failed to upload image: {}", imageUrl, e);
        buffer.replace(matcher.start(), matcher.end(), "(图片无法显示)");
      }
    }

    return buffer.toString();
  }

  @Override
  public synchronized void handle(Todos todos) {
    final var items =
        todos == null || todos.todos() == null
            ? ""
            : todos.todos().stream().map(this::formatTodoItem).collect(Collectors.joining("\n"));
    final var markdown = items.isEmpty() ? "" : "---\n**待办事项**\n" + items;
    log.info(
        "updateTodoList: cardId={}, itemCount={}",
        cardId,
        todos != null ? todos.todos().size() : 0);
    sendElementContent("todo", markdown);
  }
}
