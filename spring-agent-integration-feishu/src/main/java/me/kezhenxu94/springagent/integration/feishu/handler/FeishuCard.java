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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.web.client.RestTemplate;

/**
 * One card, as the thing that writes to it: the Feishu client, the card's id, the queue every
 * change waits in, and the counter every write draws its sequence from.
 *
 * <p>Apart from {@link FeishuCardUpdater} because a card has more than one writer. The run streams
 * its answer into the card's own elements, and every subagent it starts streams into a panel of its
 * own — the same card, different elements, one updater each.
 *
 * <p>What those writers cannot each have is a counter. The sequence is the card's, not the
 * element's: a write to one element has to carry a higher number than the write to any other
 * element before it, or the card refuses it. So every operation on this card draws from the one
 * counter here, and only ever from the thread draining the queue — a number taken anywhere else
 * could be overtaken on the way out, and everything after it would be refused. That is why the
 * writers were split from the card and not the other way round, and why anything new that writes to
 * a card belongs in this class rather than beside its caller.
 *
 * <p><b>Nothing a run says costs it a round trip.</b> A write is put in the queue and the run
 * carries on; one worker at a time drains the queue, drawing the sequence and making the call with
 * no lock held. That is two problems at once. Every chunk the model produces arrives here as the
 * whole answer so far, and each used to be an HTTP call made on the thread consuming the model's
 * stream — a Reactor worker — so a turn's cost was the number of chunks times a round trip.
 * Coalescing is free precisely because what arrives is cumulative: a queued write is not delayed by
 * the next one, it is replaced by it, and only the newest state was ever going to be visible. And
 * because the call is made outside the lock, a subagent writing into its panel no longer waits out
 * the parent's call to write into the card they share.
 *
 * <p>What a caller cannot then have is the answer: a queued write has not been made yet, so it
 * cannot say whether it landed. The few writes whose caller has to know — putting an element on the
 * card before anything can be streamed into it, and finishing the card — say so by waiting, and pay
 * a round trip for it. They are the ones that happen once per run rather than once per chunk, which
 * is what makes that affordable. See {@link #await}.
 *
 * <p><b>A card has a size, and a turn does not.</b> Feishu refuses a write that would take a card
 * over 30KB or over 200 elements, and neither is a limit a run can be asked to stay within — how
 * much the model says and how many tools it calls are the turn's to decide. So a card that fills up
 * is finished and replied to with another, as many times as the turn needs, and the run carries on
 * writing into this same object: see {@link #continueOnNewCard()}.
 *
 * <p>The image keys are here for the same reason they are shared: the same picture may be in a
 * subagent's report and in the answer the run writes from it, and the tenant only needs it once.
 */
@Slf4j
public class FeishuCard {

  private static final int CODE_STREAMING_MODE_CLOSED = 300309;

  /**
   * What Feishu says when a card cannot hold any more: {@code 200860} when its content is over the
   * 30KB a card is allowed, {@code 300305} when it is over the 200 elements one may carry.
   *
   * <p>Two codes, one situation — the card is full — and one remedy, which is {@link
   * #continueOnNewCard()}: a long turn is not a bug to be capped, and neither limit is one a run
   * can be written to stay under, since what the model says and how many tools it calls are not
   * ours to decide.
   */
  private static final int CODE_CARD_OVER_MAX_SIZE = 200860;

  private static final int CODE_TOO_MANY_ELEMENTS = 300305;

  private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\(([^)\\s]+)\\)");

  private static final String FILE_SCHEME = "file:";

  private final Client feishu;

  /**
   * The card being written to, which is not necessarily the card this started as: see {@link
   * #continueOnNewCard()}. Read on every call, so that a rollover reaches everything holding this
   * object — the updaters, the subagent panels, the question handler — without any of them being
   * told.
   */
  @Getter private volatile String cardId;

  /**
   * How many cards the run has been through, which is what a writer compares against to notice that
   * the card it had put its elements on is not the card being written to any more. Bumped last,
   * once the new card is the one this object writes to.
   */
  @Getter private volatile long generation;

  /**
   * Where a card to continue on comes from, or null on a card that cannot be continued — a
   * background notice, a test. Set rather than passed to the constructor because what it does needs
   * this card: it replies a second card onto the same message and hands the run's stop button over
   * to it, which is {@code FeishuCardListener}'s to do and not this class's.
   */
  private volatile Continuation continuation;

  /**
   * Somewhere for the run to carry on writing once a card is full: a new card, replied onto the
   * same message as the one being left behind, or null if one could not be made.
   */
  public interface Continuation {
    String newCard(String fullCardId);
  }

  private final RestTemplate restTemplate;
  private final HomeDir home;
  private final FeishuMessages messages;

  /**
   * How long the card may go between writes, and how many characters may pile up before one goes
   * out early — whichever comes first. Zero on either turns that trigger off, and a zero interval
   * writes every update through as it arrives.
   */
  private final Duration streamInterval;

  private final int streamCharacters;

  /**
   * What puts a queue that is waiting out its interval back on the clock. Null along with a zero
   * interval, on a card that holds nothing back.
   */
  private final ScheduledExecutorService clock;

  /**
   * Where the queue is drained, and so where every call to Feishu is made. Null on a card that
   * writes on whichever thread asked it to — which is every card but the one a run streams into,
   * and is what the clock and this being absent together mean.
   */
  private final Executor writes;

  private final AtomicInteger sequence = new AtomicInteger(2);
  private final ConcurrentMap<String, String> imageKeysBySource = new ConcurrentHashMap<>();

  /**
   * What the card has been told to do and has not done yet, in the order it will be done. Guarded
   * by this card's lock, like everything else here that is not the calls themselves.
   */
  private final List<Op> queued = new ArrayList<>();

  /**
   * What each element last had accepted, so an update that changes nothing costs no call.
   *
   * <p>Recorded when the call returns rather than when it is made, which is what makes it the
   * answer to what the card actually holds — which is where {@link #continuedFrom} comes from when
   * a card fills up, and is one a write that was refused must not be counted towards.
   */
  private final Map<String, String> sent = new HashMap<>();

  /**
   * Where each element's content picks up on this card, in characters of what its writer hands us:
   * nothing until the card has filled up once, and from then on the length of what the card that
   * filled up had accepted for that element.
   *
   * <p>What its writer hands us and not what was shown, so that a run on its fourth card is still
   * cutting the one answer it has been saying all along at the right place. And what was accepted
   * rather than what was sent, since the write that filled the card up was refused: counting it
   * would leave that much of the answer on neither card.
   */
  private final Map<String, Integer> continuedFrom = new HashMap<>();

  /** When the last batch of writes returned, or 0 before there has been one. */
  private long streamedAt;

  /**
   * Whether anything has landed on this card since the run moved onto it. What tells a card that
   * has filled up from a single write too big for any card: the first is worth continuing on a new
   * card, the second would fill that one too and every card after it, so it is refused instead.
   */
  private boolean wroteSinceContinuation;

  /** Whether a worker is draining the queue, so that only one ever is. */
  private boolean pumping;

  /** The drain waiting on the clock, so that only one is ever outstanding. */
  private ScheduledFuture<?> scheduledPump;

  /**
   * Set while something in the queue has a caller waiting on it, which is what suspends the
   * interval: a caller that is blocked is not helped by the queue pacing itself.
   */
  private boolean urgent;

  /**
   * Set once the run is over. Nothing may be held back after that: streaming mode is closed as the
   * card finishes, and a write arriving afterwards has to go straight out to be retried against a
   * reopened card rather than sit in a queue nothing will drain.
   */
  private boolean finished;

  /** A card that writes every update through as it arrives, on the calling thread. */
  public FeishuCard(
      final Client feishu,
      final String cardId,
      final RestTemplate restTemplate,
      final HomeDir home,
      final FeishuMessages messages) {
    this(feishu, cardId, restTemplate, home, messages, Duration.ZERO, 0, null, null);
  }

  public FeishuCard(
      final Client feishu,
      final String cardId,
      final RestTemplate restTemplate,
      final HomeDir home,
      final FeishuMessages messages,
      final Duration streamInterval,
      final int streamCharacters,
      final ScheduledExecutorService clock,
      final Executor writes) {
    this.feishu = feishu;
    this.cardId = cardId;
    this.restTemplate = restTemplate;
    this.home = home;
    this.messages = messages;
    this.streamInterval = streamInterval == null ? Duration.ZERO : streamInterval;
    this.streamCharacters = streamCharacters;
    this.clock = clock;
    this.writes = writes;
  }

  /**
   * Says where a card to carry on writing to comes from, once this one is full. Without one a full
   * card stays full: the writes that no longer fit are logged and dropped, which is what a card
   * nobody is watching — see {@link Continuation}.
   */
  public void continuation(final Continuation continuation) {
    this.continuation = continuation;
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

  /**
   * What this card shows of {@code content}, for an element whose content carries on across cards:
   * all of it on a card that has not filled up, what has been added since on one the run continued
   * onto — marked as carried on, so a card that opens mid-sentence says why — and nothing at all
   * where everything there is to say is on the card above.
   *
   * <p>Asked as well as applied, because the writer has to know whether there is anything to show
   * before it puts an element on the card to show it in.
   */
  public synchronized String continued(final String elementId, final String content) {
    final var said = Strings.nullToEmpty(content);
    final var from = continuedFrom.getOrDefault(elementId, 0);
    if (from <= 0) {
      return said;
    }
    if (said.length() <= from) {
      return "";
    }
    return messages.get("card-continued") + said.substring(from);
  }

  /** Says that {@code elementId} has joined the footer, above whatever was its top until now. */
  void footerGrewTo(final String elementId) {
    this.footerElementId = elementId;
  }

  /**
   * Adds elements to the card, above the footer, and returns whether they landed.
   *
   * @param uuid an idempotency key, so a retry cannot leave the card holding two copies
   */
  public boolean insertBeforeFooter(final String elementsJson, final String uuid) {
    return insertBefore(footerElementId, elementsJson, uuid);
  }

  /**
   * Adds elements to the card immediately above {@code targetElementId}, and returns whether they
   * landed. The element has to be one the card already has, which is why the anchors are the
   * elements every run carries — see {@code FeishuCardElements}.
   *
   * <p>Waited on, unlike a streaming write, because nothing can be written into an element the card
   * does not have: the caller's next act depends on the answer. Affordable because it is asked once
   * per element per run rather than once per chunk.
   *
   * @param uuid an idempotency key, so a retry cannot leave the card holding two copies
   */
  public boolean insertBefore(
      final String targetElementId, final String elementsJson, final String uuid) {
    return await(new Insert(targetElementId, elementsJson, uuid, new CompletableFuture<>()));
  }

  /**
   * Streams {@code content} into one element, replacing whatever it held — as soon as the card's
   * update rate allows, which is not necessarily now and is never on this thread.
   *
   * <p>A write that nothing follows — the last chunk before a tool call, the end of the answer — is
   * what the clock is for: the queue puts itself back on it rather than waiting for a chunk that
   * may not come.
   */
  public void stream(final String elementId, final String content) {
    enqueue(new Stream(elementId, Strings.nullToEmpty(content), false, null));
  }

  /**
   * The same, for an element the run goes on adding to over a turn — its answer, a subagent's
   * report. What is written is the whole of it every time, and what the card shows is the part of
   * it this card is responsible for: see {@link #continued}.
   */
  public void streamContinuing(final String elementId, final String content) {
    enqueue(new Stream(elementId, Strings.nullToEmpty(content), true, null));
  }

  /**
   * Replaces one element of the card outright, for a change no streamed content can express — a
   * different title, say, or a pane rebuilt around one more tool call. Queued like a streaming
   * write, and superseded by the next replacement of the same element, since a replacement carries
   * the element whole and the newest is the only one worth sending.
   *
   * <p>Failures are logged and left: a panel that keeps its old title is worth more than a run that
   * ends here.
   */
  public void replace(final String elementId, final String elementJson, final String uuid) {
    enqueue(new Replace(elementId, elementJson, uuid, null));
  }

  /**
   * The same, for the one caller that goes on to write into what the replacement brought with it
   * and so has to know that it is there — see {@code FeishuCardUpdater#spendOnCard}.
   */
  public boolean replaceNow(final String elementId, final String elementJson, final String uuid) {
    return await(new Replace(elementId, elementJson, uuid, new CompletableFuture<>()));
  }

  /** Takes the stop button off the card and closes streaming mode, once the run is over. */
  public void finish() {
    log.info("Finalizing card: cardId={}", cardId);
    synchronized (this) {
      // Before the queue is drained, so that what is in it goes out while the card still accepts
      // it: streaming mode is closed by the very operation being queued here.
      finished = true;
    }
    await(new Finish(new CompletableFuture<>()));
  }

  // ---------------------------------------------------------------------------------------------
  // The queue
  // ---------------------------------------------------------------------------------------------

  /**
   * One change to the card, waiting its turn.
   *
   * <p>{@link #elementId()} is what decides whether a newer change supersedes this one, so an
   * insert answers with its own idempotency key: it creates elements rather than changing one, and
   * two inserts are never the same change.
   */
  private sealed interface Op {
    String elementId();

    /** The caller waiting to hear whether this landed, or null when nobody is. */
    CompletableFuture<Boolean> landed();
  }

  private record Stream(
      String elementId, String content, boolean continuing, CompletableFuture<Boolean> landed)
      implements Op {}

  private record Replace(
      String elementId, String elementJson, String uuid, CompletableFuture<Boolean> landed)
      implements Op {}

  private record Insert(
      String targetElementId, String elementsJson, String uuid, CompletableFuture<Boolean> landed)
      implements Op {
    @Override
    public String elementId() {
      return uuid;
    }
  }

  private record Finish(CompletableFuture<Boolean> landed) implements Op {
    @Override
    public String elementId() {
      return FeishuCardElements.STOP;
    }
  }

  /** Queues {@code op} and gets the queue moving, without waiting for it to be sent. */
  private void enqueue(final Op op) {
    synchronized (this) {
      add(op);
    }
    pump();
  }

  /**
   * Queues {@code op} and waits for it, which is also what drains everything queued ahead of it:
   * the queue is drained in order, so the run's own words reach the card before the element that is
   * being put on it or the settings that close it.
   */
  private boolean await(final Op op) {
    enqueue(op);
    try {
      return op.landed().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for a write to card {}", cardId);
      return false;
    } catch (ExecutionException e) {
      log.warn("A write to card {} could not be made", cardId, e.getCause());
      return false;
    }
  }

  /**
   * Adds {@code op} to the queue, superseding the last change queued to the same element where that
   * is a change of the same kind: a streaming write replaces a streaming write, a replacement
   * replaces a replacement. Anything queued in between is about some other element and cannot be
   * reordered against this one, which is why the scan stops at the first change to this element
   * rather than at the end of the queue.
   *
   * <p>A change someone is waiting on is never superseded and never superseded into — the caller is
   * owed an answer about the change it asked for, not about a later one.
   */
  private void add(final Op op) {
    if (op.landed() != null) {
      urgent = true;
      queued.add(op);
      return;
    }
    for (var i = queued.size() - 1; i >= 0; i--) {
      final var earlier = queued.get(i);
      if (!earlier.elementId().equals(op.elementId())) {
        continue;
      }
      if (earlier.landed() == null && earlier.getClass() == op.getClass()) {
        queued.set(i, op);
        return;
      }
      break;
    }
    queued.add(op);
  }

  /**
   * Puts a worker on the queue, unless one is already draining it: whoever is draining rechecks the
   * queue under the lock before it stops, so what was just queued cannot be left behind.
   */
  private void pump() {
    synchronized (this) {
      if (queued.isEmpty() || pumping) {
        return;
      }
      pumping = true;
    }
    if (writes == null) {
      drain();
      return;
    }
    try {
      writes.execute(this::drain);
    } catch (RejectedExecutionException e) {
      // Shutting down. The card is still owed what is queued, and the calling thread is what is
      // left to write it — a caller waiting on this one would otherwise wait for ever.
      log.warn("Card {} is being written from the calling thread: {}", cardId, e.getMessage());
      drain();
    }
  }

  private void drain() {
    try {
      while (drainOnce()) {
        // Again, for whatever was queued while the batch before it was being sent.
      }
    } catch (Throwable t) {
      log.error("Card {} stopped draining what it had queued", cardId, t);
      abandonQueued();
    }
  }

  /**
   * Sends what is queued, and returns whether to look again. Nothing is held while the calls are
   * made: the lock covers the queue, not the round trips, which is what lets the run and every
   * subagent of it write to one card without queueing behind each other's calls.
   *
   * <p>The interval starts again from when the last call returned rather than from when it was
   * sent: it is there to keep the card's writers off the run's thread, and a slow card would
   * otherwise be the one that got written to most often.
   */
  private boolean drainOnce() {
    final List<Op> batch;
    synchronized (this) {
      if (scheduledPump != null) {
        scheduledPump.cancel(false);
        scheduledPump = null;
      }
      if (queued.isEmpty()) {
        pumping = false;
        return false;
      }
      if (!dueNow()) {
        schedulePump();
        pumping = false;
        return false;
      }
      urgent = false;
      batch = takeBatch();
    }
    try {
      for (final var op : batch) {
        send(op);
      }
    } finally {
      synchronized (this) {
        streamedAt = System.nanoTime();
      }
    }
    return true;
  }

  /**
   * Whether what is queued goes out now: because a caller is waiting on it, because the run is
   * over, because this card holds nothing back, because the interval has passed since the last
   * write returned, or because enough characters have piled up that waiting out the rest of it
   * would show the reader an answer well behind the run.
   */
  private boolean dueNow() {
    if (urgent
        || finished
        || clock == null
        || streamInterval.isZero()
        || streamInterval.isNegative()) {
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
    return streamCharacters > 0 && queuedCharacters() >= streamCharacters;
  }

  /** How far behind the card is, in characters, across every element waiting to be written. */
  private int queuedCharacters() {
    var characters = 0;
    for (final var op : queued) {
      if (op instanceof Stream waiting) {
        characters +=
            Math.abs(
                waiting.content().length() - sent.getOrDefault(waiting.elementId(), "").length());
      }
    }
    return characters;
  }

  /** Empties the queue, dropping the streaming writes whose content the card already has. */
  private List<Op> takeBatch() {
    final var batch = new ArrayList<Op>(queued.size());
    for (final var op : queued) {
      if (op instanceof Stream write) {
        if (write.content().equals(sent.get(write.elementId()))) {
          if (write.landed() != null) {
            write.landed().complete(true);
          }
          continue;
        }
      }
      batch.add(op);
    }
    queued.clear();
    return batch;
  }

  private void schedulePump() {
    final var waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - streamedAt);
    final var delay = Math.max(streamInterval.toMillis() - waited, 1);
    scheduledPump = clock.schedule(this::pump, delay, TimeUnit.MILLISECONDS);
  }

  /**
   * What is left when the worker itself fell over rather than a call failing: whoever is waiting is
   * told it did not land, since nothing is going to drain the queue on their behalf.
   */
  private synchronized void abandonQueued() {
    pumping = false;
    for (final var op : queued) {
      if (op.landed() != null) {
        op.landed().complete(false);
      }
    }
    queued.clear();
  }

  /**
   * Makes one queued change, on the worker's thread. A failure is the change's own — logged, and
   * reported to whoever was waiting — and never the batch's: the writes after it are about other
   * elements and have a card to land on.
   */
  private void send(final Op op) {
    var landed = false;
    try {
      landed =
          switch (op) {
            case Stream write -> streamed(write);
            case Replace change -> update(change);
            case Insert insert -> insert(insert);
            case Finish ignored -> close();
          };
    } catch (Exception e) {
      log.warn("Failed to write {} to card {}", op.elementId(), cardId, e);
    } finally {
      if (op.landed() != null) {
        op.landed().complete(landed);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The calls, all of them made from the worker draining the queue and nowhere else
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether Feishu is saying the card cannot hold what it was just sent, whichever limit it hit.
   */
  private static boolean full(final int code) {
    return code == CODE_CARD_OVER_MAX_SIZE || code == CODE_TOO_MANY_ELEMENTS;
  }

  /**
   * Finishes this card and carries on writing to a new one, replied onto the same message, so that
   * a turn longer than a card can hold is shown across as many cards as it takes rather than
   * stopping at the first limit it meets. Returns whether there is a card to carry on writing to.
   *
   * <p>Nothing is copied over. What the run had already said stays on the card above, which is
   * where the reader was reading it, and this object goes on being the run's card with a different
   * id behind it — the writers notice by {@link #generation()}, put their elements on the new card
   * as they next write to each, and continue from where the card that filled up left off. Copying
   * would be worse than useless: the elements are the reason the card filled up, so a card that
   * arrived carrying them would be full before the run wrote a word.
   *
   * <p>The new card is made before the old one is closed. Closing first would leave a run whose
   * tenant refused the second card with nowhere to write at all, and a card that is full is still a
   * card a reader can see.
   *
   * <p>Made from the thread draining the queue and nowhere else, which is what lets it close a card
   * — a queued operation — without queueing anything.
   */
  private boolean continueOnNewCard() {
    final var handOver = continuation;
    if (handOver == null) {
      return false;
    }
    synchronized (this) {
      if (!wroteSinceContinuation) {
        // Nothing has fitted on this card, so nothing would fit on the next one either: what is
        // being written is bigger than a card, and continuing would reply a card per attempt.
        log.error("Card {} is full and so would every card after it be", cardId);
        return false;
      }
    }
    final var full = cardId;
    final var continued = handOver.newCard(full);
    if (Strings.isNullOrEmpty(continued)) {
      log.error("Card {} is full and there is no card to continue it on", full);
      return false;
    }
    // The card being left behind, finished: its stop button belongs to the card the run is now
    // writing to, and a card left in streaming mode goes on saying it is being written to.
    close();
    synchronized (this) {
      cardId = continued;
      sent.forEach((elementId, content) -> continuedFrom.put(elementId, content.length()));
      // A card's sequence is its own, and the new one has been written to once, by the reply that
      // sent it.
      sequence.set(2);
      sent.clear();
      footerElementId = FeishuCardElements.USAGE;
      wroteSinceContinuation = false;
      generation++;
    }
    log.info("Card {} was full, and run continues on card {}", full, continued);
    return true;
  }

  /** Records that the card has taken something, which is what makes it worth continuing. */
  private synchronized void wrote() {
    wroteSinceContinuation = true;
  }

  /** One streaming write, remembered by what the card took rather than by what it was handed. */
  private boolean streamed(final Stream write) {
    final var showing =
        write.continuing() ? continued(write.elementId(), write.content()) : write.content();
    if (showing.isEmpty() && write.continuing()) {
      // Everything there is to show is on the card this one continues: nothing to send, and
      // nothing accepted here to move the cut on by.
      return true;
    }
    // The images last, and here rather than where the content was written: a run renders its whole
    // answer on every chunk, so uploading as it was written meant uploading on the run's thread,
    // and once per chunk instead of once per call that carries it.
    if (!stream(write.elementId(), reuploadImages(showing), true)) {
      return false;
    }
    synchronized (this) {
      sent.put(write.elementId(), write.content());
    }
    return true;
  }

  @SneakyThrows
  private boolean stream(final String elementId, final String content, final boolean allowRetry) {
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
        return stream(elementId, content, false);
      }
      // Not retried here, even once there is a card to write to: this element is not on that card
      // yet, and putting it there is the writer's to do — see continueOnNewCard().
      if (full(response.getCode())) {
        continueOnNewCard();
      }
      return false;
    }
    wrote();
    return true;
  }

  @SneakyThrows
  private boolean insert(final Insert insert) {
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
                            .targetElementId(insert.targetElementId())
                            .uuid(insert.uuid())
                            .sequence(seq)
                            .elements(insert.elementsJson())
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to insert elements before {}: cardId={}, seq={}, code={}, msg={}",
          insert.targetElementId(),
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
      if (full(response.getCode())) {
        continueOnNewCard();
      }
      return false;
    }
    wrote();
    return true;
  }

  @SneakyThrows
  private boolean update(final Replace change) {
    final var seq = sequence.getAndIncrement();
    final var response =
        feishu
            .cardkit()
            .v1()
            .cardElement()
            .update(
                UpdateCardElementReq.newBuilder()
                    .cardId(cardId)
                    .elementId(change.elementId())
                    .updateCardElementReqBody(
                        UpdateCardElementReqBody.newBuilder()
                            .uuid(change.uuid())
                            .sequence(seq)
                            .element(change.elementJson())
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.warn(
          "Failed to update element {}: cardId={}, seq={}, code={}, msg={}",
          change.elementId(),
          cardId,
          seq,
          response.getCode(),
          response.getMsg());
      if (full(response.getCode())) {
        continueOnNewCard();
      }
      return false;
    }
    wrote();
    return true;
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

  /** The stop button off the card and streaming mode closed, the run being over. */
  @SneakyThrows
  private boolean close() {
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
      return false;
    }
    log.info("Card finalized: cardId={}", cardId);
    return true;
  }

  /**
   * Replaces every markdown image whose target is a local file or a URL with the key Feishu hands
   * back for it, since a card can only show images the tenant has uploaded. Tools produce local
   * paths — {@code GenerateImage} saves into the user's artifacts directory — so this is where they
   * become something a card can render.
   *
   * <p>Idempotent, which is what lets it be applied where the content is sent rather than where it
   * was written: a key is neither a path nor a URL, so content that has been through this once is
   * left alone the next time.
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
      // The same picture may be in a subagent's report and in the answer the run writes from it,
      // and a run re-renders its whole answer on every chunk. An empty value caches a failure.
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
