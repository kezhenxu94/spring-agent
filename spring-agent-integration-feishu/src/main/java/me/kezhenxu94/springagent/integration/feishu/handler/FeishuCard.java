package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.web.client.RestTemplate;

/**
 * One card, as the thing that writes to it: the Feishu client, the card's id, and the counter every
 * write draws its sequence from.
 *
 * <p>Apart from {@link FeishuCardUpdater} because a card has more than one writer. The run streams
 * its answer into the card's own elements, and every subagent it starts streams into a panel of its
 * own — the same card, different elements, one updater each.
 *
 * <p>What those writers cannot each have is a counter. The sequence is the card's, not the
 * element's: a write to one element has to carry a higher number than the write to any other
 * element before it, or the card refuses it. So every operation on this card draws from the one
 * counter here, and draws it inside the same lock as the call that carries it — a number taken
 * outside the lock could be overtaken on the way out, and everything after it would be refused.
 * That is why the writers were split from the card and not the other way round, and why anything
 * new that writes to a card belongs in this class rather than beside its caller.
 *
 * <p>The image keys are here for the same reason they are shared: the same picture may be in a
 * subagent's report and in the answer the run writes from it, and the tenant only needs it once.
 */
@Slf4j
@RequiredArgsConstructor
public class FeishuCard {

  private static final int CODE_STREAMING_MODE_CLOSED = 300309;

  private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)\\s]+)\\)");

  private static final String FILE_SCHEME = "file:";

  private final Client feishu;
  private final String cardId;
  private final RestTemplate restTemplate;
  private final HomeDir home;
  private final FeishuMessages messages;

  private final AtomicInteger sequence = new AtomicInteger(2);
  private final ConcurrentMap<String, String> imageKeysBySource = new ConcurrentHashMap<>();

  public String cardId() {
    return cardId;
  }

  /**
   * The topmost element of the footer, which is what "above the footer" has to mean for an insert.
   *
   * <p>It starts at the spend row, which every card is sent carrying — the conversation hint is
   * below it and the stop button rides in it — and moves up as the footer grows: the sources join
   * it above once a turn has retrieved anything. Anchored on the hint alone, a subagent's panel
   * would come to rest under the spend row, which is the one thing the footer is for keeping at the
   * bottom.
   */
  private volatile String footerElementId = FeishuCardElements.USAGE;

  /** Says that {@code elementId} has joined the footer, above whatever was its top until now. */
  void footerGrewTo(final String elementId) {
    this.footerElementId = elementId;
  }

  /**
   * Adds elements to the card, above the footer, and returns whether they landed.
   *
   * @param uuid an idempotency key, so a retry cannot leave the card holding two copies
   */
  public synchronized boolean insertBeforeFooter(final String elementsJson, final String uuid) {
    return insertBefore(footerElementId, elementsJson, uuid);
  }

  /**
   * Adds elements to the card immediately above {@code targetElementId}, and returns whether they
   * landed. The element has to be one the card already has, which is why the anchors are the
   * elements every run carries — see {@code FeishuCardElements}.
   *
   * @param uuid an idempotency key, so a retry cannot leave the card holding two copies
   */
  @SneakyThrows
  public synchronized boolean insertBefore(
      final String targetElementId, final String elementsJson, final String uuid) {
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
                            .targetElementId(targetElementId)
                            .uuid(uuid)
                            .sequence(seq)
                            .elements(elementsJson)
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to insert elements before {}: cardId={}, seq={}, code={}, msg={}",
          targetElementId,
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
      return false;
    }
    return true;
  }

  /** Streams {@code content} into one element, replacing whatever it held. */
  public synchronized void stream(final String elementId, final String content) {
    stream(elementId, content, true);
  }

  @SneakyThrows
  private synchronized void stream(
      final String elementId, final String content, final boolean allowRetry) {
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
        stream(elementId, content, false);
      }
    }
  }

  /**
   * Replaces one element of the card outright, for a change no streamed content can express — a
   * different title, say, or a row growing a column. Failures are logged and left: a panel that
   * keeps its old title is worth more than a run that ends here. Returns whether it landed, for the
   * callers that go on to write into what the replacement brought with it.
   */
  @SneakyThrows
  public synchronized boolean replace(
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
      return false;
    }
    return true;
  }

  @SneakyThrows
  private synchronized void reenableStreaming() {
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

  /** Takes the stop button off the card and closes streaming mode, once the run is over. */
  @SneakyThrows
  public synchronized void finish() {
    log.info("Finalizing card: cardId={}", cardId);

    final var removeActionsResponse =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .delete(
                DeleteCardElementReq.newBuilder()
                    .cardId(cardId)
                    // The button inside the spend row, not the row: the spend line stays on the
                    // card after the run that wrote it has ended.
                    .elementId(FeishuCardElements.STOP)
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

  /**
   * Replaces every markdown image whose target is a local file or a URL with the key Feishu hands
   * back for it, since a card can only show images the tenant has uploaded. Tools produce local
   * paths — {@code GenerateImage} saves into the user's artifacts directory — so this is where they
   * become something a card can render.
   */
  String reuploadImages(final String content) {
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
      if (!home.contains(path) || !Files.isRegularFile(path)) {
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
}
