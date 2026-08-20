package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.enums.CreateCardElementTypeEnum;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReqBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.openai.models.completions.CompletionUsage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContextKey;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class FeishuCardUpdater implements AgentResponseListener, TodoEventHandler {
  public static final ToolContextKey<FeishuCardUpdater> TOOL_CONTEXT_KEY =
      new ToolContexts.Key<>("FeishuCardUpdater", FeishuCardUpdater.class);

  private static final int CODE_STREAMING_MODE_CLOSED = 300309;

  /**
   * The divider above the card's footer, and so the anchor anything added mid-run is placed before:
   * it keeps the usage line and the conversation hint at the bottom where a reader expects them.
   */
  private static final String FOOTER_ELEMENT_ID = "guide_divider";

  private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)\\s]+)\\)");

  private static final String FILE_SCHEME = "file:";

  private static final String DESCRIPTION_FIELD = "description";

  private final Client feishu;
  private final JsonMapper om;
  private final String cardId;
  private final String userId;
  private final RestTemplate restTemplate;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing;
  private final FeishuMessages messages;
  private final Instant startedAt = Instant.now();
  private final AtomicInteger sequence = new AtomicInteger(2);
  private final ConcurrentMap<String, String> imageKeysBySource = new ConcurrentHashMap<>();
  private String lastBaseContent = "";

  public FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final String userId,
      final RestTemplate restTemplate,
      final UserWorkspaceFactory userWorkspaceFactory,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages) {
    this.feishu = feishu;
    this.om = om;
    this.cardId = cardId;
    this.userId = userId;
    this.restTemplate = restTemplate;
    this.userWorkspaceFactory = userWorkspaceFactory;
    this.modelPricing = modelPricing != null ? modelPricing : Map.of();
    this.messages = messages;
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

  /**
   * Appends the failure below whatever the run had already said, rather than replacing it: the
   * answer written before the error is usually most of one, and losing it costs the reader more
   * than the error tells them.
   *
   * <p>The base content is left untouched, so a later update — a retry that resumes streaming —
   * clears the failure instead of writing underneath it.
   */
  private synchronized void showError(Throwable error) {
    final var summary = errorDisplay(error);
    log.warn("showError: cardId={}, error={}", cardId, summary);
    final var notice =
        messages.error(summary)
            + "\n\n```\n"
            + Throwables.getStackTraceAsString(error).stripTrailing()
            + "\n```";
    final var base = Strings.nullToEmpty(lastBaseContent);
    sendContent(base.isEmpty() ? notice : base + "\n\n" + notice);
  }

  private static String errorDisplay(Throwable error) {
    if (error == null) return null;
    final var msg = error.getMessage();
    return Strings.isNullOrEmpty(msg) ? error.getClass().getSimpleName() : msg;
  }

  /**
   * Announces a tool call on the card, above the fields it was called with.
   *
   * <p>Tools that take a {@code description} — {@code Bash} asks the model for one, in active
   * voice, saying what the command does — describe the call far better than its name does, so that
   * text becomes the line and is left out of the fields below rather than said twice.
   */
  public synchronized void setToolStatus(
      String toolName, String toolInput, ToolContext toolContext) {
    final var input = parseObject(toolInput);
    final var description = input == null ? null : singleLine(input.path(DESCRIPTION_FIELD));
    log.info("Tool call: cardId={}, tool={}", cardId, toolName);
    final var header =
        description != null
            ? description
            : messages.get("card-calling-tool", Strings.nullToEmpty(toolName));
    final var fields =
        input == null ? Strings.nullToEmpty(toolInput) : formatFields(input, description != null);
    sendContent(lastBaseContent + "\n" + header + quote(fields));
  }

  /** The input parsed as a JSON object, or {@code null} if it is neither JSON nor an object. */
  private JsonNode parseObject(String toolInput) {
    if (Strings.isNullOrEmpty(toolInput)) {
      return null;
    }
    try {
      final var node = om.readTree(toolInput);
      return node.isObject() ? node : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * The node as one line of text, or {@code null} if it holds no text to show. A description the
   * model wrote may span lines; the card gives a call one line, so they are folded into it.
   */
  private static String singleLine(JsonNode node) {
    if (!node.isString()) {
      return null;
    }
    final var text = node.stringValue().strip().replaceAll("\\s*\\R\\s*", " ");
    return text.isEmpty() ? null : text;
  }

  private static String formatFields(JsonNode input, boolean skipDescription) {
    return input.properties().stream()
        .filter(entry -> !skipDescription || !DESCRIPTION_FIELD.equals(entry.getKey()))
        .map(entry -> entry.getKey() + ": " + valueOf(entry.getValue()))
        .collect(Collectors.joining("\n"));
  }

  /**
   * A field holding an object or an array is shown as the JSON it is: readable enough, where {@code
   * asString()} refuses to coerce it and would cost the reader every other field with it.
   */
  private static String valueOf(JsonNode value) {
    return value.isContainer() ? value.toString() : value.asString();
  }

  private static String quote(String text) {
    if (text.isEmpty()) {
      return "";
    }
    return "\n"
        + Arrays.stream(text.split("\n"))
            .map(line -> "> " + line)
            .collect(Collectors.joining("\n"));
  }

  private synchronized void sendContent(String content) {
    sendElementContent("message", content);
  }

  private String formatTodoItem(TodoWriteTool.Todos.TodoItem item) {
    return switch (item.status()) {
      case completed -> "☑ " + item.content();
      case in_progress -> "☒ **" + item.activeForm() + "**";
      case pending -> "☐ " + item.content();
    };
  }

  /**
   * Adds elements to the card, above the footer, and returns whether they landed.
   *
   * <p>Here rather than in the caller because {@link #sequence} is: the card rejects an operation
   * whose sequence did not strictly increase, so everything writing to one card has to draw from
   * the same counter, and this is where it lives.
   *
   * @param uuid an idempotency key, so a retry cannot leave the card holding two copies
   */
  @SneakyThrows
  public synchronized boolean insertBeforeFooter(final String elementsJson, final String uuid) {
    final var seq = sequence.getAndIncrement();
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .create(
                CreateCardElementReq.newBuilder()
                    .cardId(cardId)
                    .createCardElementReqBody(
                        CreateCardElementReqBody.newBuilder()
                            .type(CreateCardElementTypeEnum.INSERT_BEFORE)
                            .targetElementId(FOOTER_ELEMENT_ID)
                            .uuid(uuid)
                            .sequence(seq)
                            .elements(elementsJson)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to insert elements: cardId={}, seq={}, code={}, msg={}",
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
      return false;
    }
    return true;
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
    updateContent(reuploadImages(contentSoFar));
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

  /**
   * Replaces every markdown image whose target is a local file or a URL with the key Feishu hands
   * back for it, since a card can only show images the tenant has uploaded. Tools produce local
   * paths — {@code GenerateImage} saves into the user's artifacts directory — so this is where they
   * become something a card can render.
   */
  String reuploadImages(String content) {
    if (Strings.isNullOrEmpty(content)) {
      return content;
    }
    final var matcher = IMAGE_PATTERN.matcher(content);
    final var out = new StringBuilder();
    while (matcher.find()) {
      final var imageName = matcher.group(1);
      final var source = matcher.group(2);
      if (!isRemote(source) && !isLocal(source)) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      // Every streaming tick re-renders the whole answer, so without this the same image would be
      // downloaded and uploaded again on each of them. An empty value caches a failure.
      final var imageKey =
          imageKeysBySource.computeIfAbsent(source, it -> Strings.nullToEmpty(uploadImage(it)));
      final var replacement =
          imageKey.isEmpty()
              ? messages.get("card-image-unavailable")
              : "![" + imageName + "](" + imageKey + ")";
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean isRemote(final String source) {
    return source.startsWith("http://") || source.startsWith("https://");
  }

  /** A {@code file://} URL, as {@code GenerateImage} returns, or a plain absolute path. */
  private static boolean isLocal(final String source) {
    return source.startsWith(FILE_SCHEME) || source.startsWith("/");
  }

  private static Path pathOf(final String source) {
    return (source.startsWith(FILE_SCHEME) ? Path.of(URI.create(source)) : Path.of(source))
        .toAbsolutePath()
        .normalize();
  }

  /**
   * Uploads the image at {@code source} to Feishu, returning its key, or {@code null} if it fails.
   */
  private String uploadImage(final String source) {
    try {
      if (isRemote(source)) {
        final var imageBytes = restTemplate.getForObject(source, byte[].class);
        if (imageBytes == null) {
          log.warn("Nothing to download at image URL: {}", source);
          return null;
        }
        final var tempFile = File.createTempFile("image-", "." + extensionOf(imageBytes));
        try {
          Files.write(tempFile.toPath(), imageBytes);
          return upload(tempFile, source);
        } finally {
          tempFile.delete();
        }
      }
      final var path = pathOf(source);
      // The same containment check VisionTools makes: a run may only show files belonging to the
      // user it is answering, never an arbitrary path the model wrote into its answer.
      if (!userWorkspaceFactory.forOwner(userId).contains(path) || !Files.isRegularFile(path)) {
        log.warn("Rejected image path outside the user's home or missing: {}", source);
        return null;
      }
      return upload(path.toFile(), source);
    } catch (Exception e) {
      log.error("Failed to upload image: {}", source, e);
      return null;
    }
  }

  private String upload(final File file, final String source) throws Exception {
    final var response =
        feishu
            .im()
            .v1()
            .image()
            .create(
                CreateImageReq.newBuilder()
                    .createImageReqBody(
                        CreateImageReqBody.newBuilder().imageType("message").image(file).build())
                    .build());
    if (!response.success()) {
      log.warn("Failed to upload image: {}, {}, {}", source, response.getCode(), response.getMsg());
      return null;
    }
    return response.getData().getImageKey();
  }

  private static String extensionOf(final byte[] imageBytes) throws IOException {
    final var contentType =
        URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(imageBytes));
    return contentType == null ? "png" : contentType.split("/")[1];
  }

  @Override
  public synchronized void handle(Todos todos) {
    final var items =
        todos == null || todos.todos() == null
            ? ""
            : todos.todos().stream().map(this::formatTodoItem).collect(Collectors.joining("\n"));
    final var markdown =
        items.isEmpty() ? "" : "---\n" + messages.get("card-todo-heading") + "\n" + items;
    log.info(
        "updateTodoList: cardId={}, itemCount={}",
        cardId,
        todos != null ? todos.todos().size() : 0);
    sendElementContent("todo", markdown);
  }
}
