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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public class FeishuCard {

  private static final int CODE_STREAMING_MODE_CLOSED = 300309;

  private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)\\s]+)\\)");

  private static final String FILE_SCHEME = "file:";

  private final Client feishu;
  private final String cardId;
  private final RestTemplate restTemplate;
  private final HomeDir home;
  private final FeishuMessages messages;

  /**
   * How long the card may go between streaming writes, and how many characters may pile up before
   * one goes out early — whichever comes first. Zero on either turns that trigger off, and a zero
   * interval writes every update through as it arrives.
   */
  private final Duration streamInterval;

  private final int streamCharacters;

  /**
   * Where a write that was held back is sent from when nothing follows it to carry it out. Null
   * along with a zero interval, on a card that holds nothing back.
   */
  private final ScheduledExecutorService flushes;

  private final AtomicInteger sequence = new AtomicInteger(2);
  private final ConcurrentMap<String, String> imageKeysBySource = new ConcurrentHashMap<>();

  /**
   * The latest content each element has been given but not yet been sent, in the order the elements
   * first fell behind. Guarded by this card's lock, like everything else that writes.
   */
  private final Map<String, String> unsent = new LinkedHashMap<>();

  /** What each element was last sent, so an update that changes nothing costs no call. */
  private final Map<String, String> sent = new HashMap<>();

  /** When the last streaming write returned, or 0 before there has been one. */
  private long streamedAt;

  /** The write waiting on the clock, so that only one is ever outstanding. */
  private ScheduledFuture<?> scheduledFlush;

  /**
   * Set once the run is over. Nothing may be held back after that: streaming mode is closed as the
   * card finishes, and a write arriving afterwards has to go straight out to be retried against a
   * reopened card rather than sit in a buffer nothing will drain.
   */
  private boolean finished;

  /** A card that writes every update through as it arrives. */
  public FeishuCard(
      final Client feishu,
      final String cardId,
      final RestTemplate restTemplate,
      final HomeDir home,
      final FeishuMessages messages) {
    this(feishu, cardId, restTemplate, home, messages, Duration.ZERO, 0, null);
  }

  public FeishuCard(
      final Client feishu,
      final String cardId,
      final RestTemplate restTemplate,
      final HomeDir home,
      final FeishuMessages messages,
      final Duration streamInterval,
      final int streamCharacters,
      final ScheduledExecutorService flushes) {
    this.feishu = feishu;
    this.cardId = cardId;
    this.restTemplate = restTemplate;
    this.home = home;
    this.messages = messages;
    this.streamInterval = streamInterval == null ? Duration.ZERO : streamInterval;
    this.streamCharacters = streamCharacters;
    this.flushes = flushes;
  }

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
    flushUnsent();
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

  /**
   * Streams {@code content} into one element, replacing whatever it held — as soon as the card's
   * update rate allows, which is not necessarily now.
   *
   * <p>Held back rather than sent because the run streams faster than the card can be written.
   * Every chunk the model produces arrives here as the whole answer so far, and each one used to be
   * an HTTP call made on the thread consuming the model's stream, under this card's lock: the run
   * went no faster than Feishu answered, and a turn's cost became the number of chunks times a
   * round trip — with a card's subagents queued behind the same lock. Coalescing is free precisely
   * because what arrives is cumulative: a write that has not gone out yet is not delayed by the
   * next one, it is replaced by it, and only the newest state was ever going to be visible anyway.
   *
   * <p>What is not free is a write that nothing follows — the last chunk before a tool call, the
   * end of the answer — so a held-back write is also put on the clock, and the operations that are
   * not streaming ({@link #replace}, {@link #insertBefore}, {@link #finish}) drain what is waiting
   * before they change the card underneath it.
   */
  public synchronized void stream(final String elementId, final String content) {
    unsent.put(elementId, Strings.nullToEmpty(content));
    if (dueNow()) {
      flushUnsent();
    } else {
      scheduleFlush();
    }
  }

  /**
   * Whether what is waiting goes out now: because this card holds nothing back, because the
   * interval has passed since the last write returned, or because enough characters have piled up
   * that waiting out the rest of it would show the reader an answer well behind the run.
   */
  private boolean dueNow() {
    if (finished || flushes == null || streamInterval.isZero() || streamInterval.isNegative()) {
      return true;
    }
    if (streamedAt == 0) {
      // The first thing the card says appears at once. Nothing is on it yet, so there is no
      // updating to rate-limit — only the wait before it stops looking empty.
      return true;
    }
    if (System.nanoTime() - streamedAt >= streamInterval.toNanos()) {
      return true;
    }
    return streamCharacters > 0 && unsentCharacters() >= streamCharacters;
  }

  /** How far behind the card is, in characters, across every element waiting to be written. */
  private int unsentCharacters() {
    var characters = 0;
    for (final var waiting : unsent.entrySet()) {
      characters +=
          Math.abs(waiting.getValue().length() - sent.getOrDefault(waiting.getKey(), "").length());
    }
    return characters;
  }

  /**
   * Sends everything waiting, and starts the interval again from when the last of them returned
   * rather than from when it was sent: the interval is there to leave the run's own thread free,
   * and a slow card would otherwise be the one that got written to most often.
   */
  private synchronized void flushUnsent() {
    if (scheduledFlush != null) {
      scheduledFlush.cancel(false);
      scheduledFlush = null;
    }
    if (unsent.isEmpty()) {
      return;
    }
    final var writes = new ArrayList<>(unsent.entrySet());
    unsent.clear();
    try {
      for (final var write : writes) {
        if (write.getValue().equals(sent.get(write.getKey()))) {
          continue;
        }
        sent.put(write.getKey(), write.getValue());
        stream(write.getKey(), write.getValue(), true);
      }
    } finally {
      streamedAt = System.nanoTime();
    }
  }

  /**
   * Puts the writes that are waiting on the clock, so that a run which goes quiet — a tool call
   * that takes a minute, an answer that has ended — still leaves the card showing what it last
   * said. One at a time: whichever write drains the buffer drains all of it.
   */
  private void scheduleFlush() {
    if (scheduledFlush != null) {
      return;
    }
    final var waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - streamedAt);
    final var delay = Math.max(streamInterval.toMillis() - waited, 1);
    scheduledFlush = flushes.schedule(this::flushOnTime, delay, TimeUnit.MILLISECONDS);
  }

  private void flushOnTime() {
    synchronized (this) {
      scheduledFlush = null;
      try {
        flushUnsent();
      } catch (Exception e) {
        // The next chunk writes the same content again, and the run must not end on a card update.
        log.warn("Failed to flush what card {} had waiting", cardId, e);
      }
    }
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
    // Before the replacement, or a write still waiting for this element would land on top of it
    // afterwards — the old content, carrying the newer sequence, over the element that replaced it.
    flushUnsent();
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
    // The last thing the run said is usually still waiting here, and streaming mode is closed
    // below: after that a held-back write has no card left to stream into.
    flushUnsent();
    finished = true;

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
