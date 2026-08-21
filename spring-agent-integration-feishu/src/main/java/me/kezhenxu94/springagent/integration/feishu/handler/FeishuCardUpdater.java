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
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardElementReqBody;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import me.kezhenxu94.springagent.core.agent.AgentResponseListener.SubagentEvent;
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

  /** How much of a subagent's report a panel holds. See {@link #truncated}. */
  private static final int MAX_SUBAGENT_REPORT = 3000;

  private final Client feishu;
  private final JsonMapper om;
  private final String cardId;
  private final String userId;
  private final RestTemplate restTemplate;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing;
  private final FeishuMessages messages;
  private final FeishuSubagentPanel panels;
  private final Instant startedAt = Instant.now();
  private final AtomicInteger sequence = new AtomicInteger(2);
  private final ConcurrentMap<String, String> imageKeysBySource = new ConcurrentHashMap<>();

  /**
   * What the turn has spent, across every model call it made and every subagent it started. Guarded
   * by the same lock as the footer it is written to, which is every writer of it.
   */
  private final Spend turnSpend = new Spend();

  /**
   * The panels on this card, by subagent id. Kept because a panel is replaced whole when its
   * subagent ends, and that replacement has to carry what the panel had been streaming; dropped as
   * it ends, since a panel is never rewritten twice.
   */
  private final ConcurrentMap<String, SubagentPanel> subagentPanels = new ConcurrentHashMap<>();

  /** One subagent's panel: what it has said, what it has spent, and since when. */
  private final class SubagentPanel {
    private final Instant startedAt = Instant.now();
    private final Spend spend = new Spend();
    private String report = "";
  }

  /**
   * What one run spent: which models answered, how many tokens they read and wrote, and roughly
   * what that cost. One of these for the turn and one per subagent, so a reader can see both the
   * whole and where it went.
   */
  private final class Spend {
    private final Set<String> models = new LinkedHashSet<>();
    private long promptTokens;
    private long completionTokens;

    /** Per currency, so a deployment pricing two models in two of them gets two figures. */
    private final Map<String, Double> costs = new LinkedHashMap<>();

    private void model(final String model) {
      models.add(model);
    }

    private void add(final String model, final Usage usage) {
      models.add(model);
      if (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null) {
        return;
      }
      promptTokens += usage.getPromptTokens();
      completionTokens += usage.getCompletionTokens();
      final var cost = approxCost(model, usage);
      if (cost != null) {
        costs.merge(modelPricing.get(model).currency().symbol(), cost, Double::sum);
      }
    }

    /** The one line of it: models, tokens, cost and how long, or nothing at all if nothing ran. */
    private String render(final Instant since) {
      final var models = String.join(" + ", this.models);
      if (promptTokens == 0 && completionTokens == 0) {
        return models;
      }
      final var cost =
          costs.entrySet().stream()
              .map(entry -> String.format(Locale.ROOT, "~%s%.2f", entry.getKey(), entry.getValue()))
              .collect(Collectors.joining(" + "));
      return String.format(
          "%s · ↑%d ↓%d%s · %s",
          models,
          promptTokens,
          completionTokens,
          cost.isEmpty() ? "" : " · " + cost,
          formatElapsed(Duration.between(since, Instant.now())));
    }
  }

  private String lastBaseContent = "";

  public FeishuCardUpdater(
      final Client feishu,
      final JsonMapper om,
      final String cardId,
      final String userId,
      final RestTemplate restTemplate,
      final UserWorkspaceFactory userWorkspaceFactory,
      final Map<String, SpringAgentProperties.Ai.ModelPricing> modelPricing,
      final FeishuMessages messages,
      final FeishuSubagentPanel panels) {
    this.feishu = feishu;
    this.om = om;
    this.cardId = cardId;
    this.userId = userId;
    this.restTemplate = restTemplate;
    this.userWorkspaceFactory = userWorkspaceFactory;
    this.modelPricing = modelPricing != null ? modelPricing : Map.of();
    this.messages = messages;
    this.panels = panels;
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

  /**
   * What one model call cost, at the price configured for that model and for the mode it ran in, or
   * null where the model has no pricing configured. Returned rather than formatted, since the
   * footer shows the sum of every call the turn made.
   */
  private Double approxCost(String model, Usage usage) {
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
    return (usage.getPromptTokens() * inputPrice
            + usage.getCompletionTokens() * pricing.outputPerMillion())
        / 1_000_000.0;
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

  /**
   * Adds one model call to the turn's running total and rewrites the footer with it.
   *
   * <p>A total, not the latest call: a turn is a loop, and every iteration of it reports its own
   * usage — as does every subagent the turn started, whose usage is forwarded to this run's
   * listeners for exactly this reason. Showing the last report alone said a five-call turn had cost
   * what its final call cost.
   *
   * <p>Assumes one usage report per model call, which is what the OpenAI streaming API does: usage
   * arrives on the last chunk of a call and nowhere else. A gateway that instead repeated a
   * cumulative total on every chunk would inflate this.
   */
  public synchronized void updateUsageFooter(String model, Usage usage) {
    if (Strings.isNullOrEmpty(model)) {
      log.debug("updateUsageFooter: skipped, model is empty for cardId={}", cardId);
      return;
    }
    if (usage == null) {
      // Named before it has spent anything, since this is also how the footer first says which
      // model is answering.
      turnSpend.model(model);
    } else {
      turnSpend.add(model, usage);
    }
    sendElementContent("usage", turnSpend.render(startedAt));
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

  /**
   * A subagent of this run starting, saying something, spending something, or ending.
   *
   * <p>Each gets a collapsed panel of its own on the card: a run can have several going at once,
   * and what any one of them says is not the answer — the run folds that into its own reply. The
   * panel is where a reader goes to see what the work behind the reply actually was, and it streams
   * as the subagent writes, so a turn that spends five minutes on one is not five minutes of
   * nothing.
   *
   * <p>Each panel keeps a footer of its own, on the same terms as the card's: which model answered,
   * what it read and wrote, roughly what that cost and how long it took. The card's footer is the
   * turn as a whole and these are where it went, which is the only way to see that one subagent
   * accounts for most of a turn.
   *
   * <p>The panel is inserted once, streamed into by the ids of the text inside it, and replaced
   * whole when the subagent ends — replaced rather than streamed because the title has to change
   * too, and the title is not a streamable element.
   */
  @Override
  public synchronized void onSubagent(SubagentEvent event) {
    final var id = event.subagentId();
    if (Strings.isNullOrEmpty(id)) {
      return;
    }
    if (event.started()) {
      // The panel has to exist before anything can be streamed into it, and the subagent's own id
      // is
      // the idempotency key: unique to it, and the same across a retry, so a retried insert cannot
      // leave the card holding two panels.
      subagentPanels.put(id, new SubagentPanel());
      insertBeforeFooter(panels.forInsert(id, event.description(), null), id);
      return;
    }
    final var panel = subagentPanels.get(id);
    if (event.ended()) {
      subagentPanels.remove(id);
      updateElement(
          FeishuSubagentPanel.panelElementId(id),
          panels.forUpdate(
              id,
              event.description(),
              event.outcome(),
              panel == null ? truncated(event.contentSoFar()) : panel.report,
              panel == null ? "" : panel.spend.render(panel.startedAt)),
          id + ":end");
      return;
    }
    // Only from here on is the panel needed, and only a subagent whose start went missing has none.
    if (panel == null) {
      log.debug("No panel for subagent {} on card {}, nothing to update", id, cardId);
      return;
    }
    if (event.spent()) {
      panel.spend.add(event.model(), event.usage());
      sendElementContent(
          FeishuSubagentPanel.footerElementId(id), panel.spend.render(panel.startedAt));
      return;
    }
    panel.report = truncated(event.contentSoFar());
    sendElementContent(FeishuSubagentPanel.bodyElementId(id), panel.report);
  }

  /**
   * What a panel may hold. A subagent reports for the model to act on, not for a card to show, and
   * that can run to tens of thousands of characters; the card refuses an element that long and the
   * whole panel is lost with it. The head rather than the tail: a report opens with its conclusion.
   */
  private static String truncated(final String content) {
    final var text = Strings.nullToEmpty(content);
    return text.length() <= MAX_SUBAGENT_REPORT
        ? text
        : text.substring(0, MAX_SUBAGENT_REPORT) + "\n\n…";
  }

  /**
   * Replaces one element of the card outright, for a change no streamed content can express — a
   * different title, say. Failures are logged and left: a panel that keeps its old title is worth
   * more than a run that ends here.
   */
  @SneakyThrows
  private synchronized void updateElement(
      final String elementId, final String elementJson, final String uuid) {
    final var seq = sequence.getAndIncrement();
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .update(
                UpdateCardElementReq.newBuilder()
                    .cardId(cardId)
                    .elementId(elementId)
                    .updateCardElementReqBody(
                        UpdateCardElementReqBody.newBuilder()
                            .uuid(uuid)
                            .sequence(seq)
                            .element(elementJson)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to update element {}: cardId={}, seq={}, code={}, msg={}",
          elementId,
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
    }
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
