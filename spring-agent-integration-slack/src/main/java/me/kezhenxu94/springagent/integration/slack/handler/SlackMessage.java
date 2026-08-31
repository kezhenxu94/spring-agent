package me.kezhenxu94.springagent.integration.slack.handler;

import com.slack.api.methods.MethodsClient;
import com.slack.api.model.block.LayoutBlock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * One reply, as the thing that writes to it: the Slack client, the channel and timestamp it was
 * posted as, and the queue every change waits in.
 *
 * <p><b>Nothing a run says costs it a round trip.</b> Every chunk the model produces arrives as the
 * whole answer so far, so a write is put here and the run carries on; one worker at a time drains
 * the queue and makes the call with no lock held. Coalescing is free precisely because what arrives
 * is cumulative: a queued write is not delayed by the next one, it is <em>replaced</em> by it, and
 * only the newest state was ever going to be visible. Without this a turn costs one {@code
 * chat.update} per chunk, made on the thread consuming the model's stream — a Reactor worker, which
 * is the scarce resource here.
 *
 * <p><b>Slack rate-limits {@code chat.update}</b> to roughly one call per second per channel and
 * answers a burst with 429 and a {@code Retry-After}. That is the failure this class will actually
 * meet in production, so the interval below is a floor rather than a nicety and the worker sleeps
 * out a {@code Retry-After} rather than dropping the write. It sleeps on a virtual thread, so the
 * cost of waiting is a stack rather than a thread.
 *
 * <p><b>A message has a size, and a turn does not.</b> Slack refuses a message over 50 blocks, and
 * that is not a limit a run can be asked to stay within — how much the model says and how many
 * tools it calls are the turn's to decide. So a reply that fills up is finished and another is
 * posted into the same thread, as many times as the turn needs, and the run carries on writing into
 * this same object: see {@link #continueInNewMessage()}.
 *
 * <p>Unlike its Feishu counterpart there is no sequence counter and no per-element addressing.
 * {@code chat.update} replaces the whole message, so a write is the complete block list rendered
 * from the updater's state — which is why ordering between writers needs nothing but the queue, and
 * why a write that loses a race costs nothing: the next one carries everything anyway.
 */
@Slf4j
public class SlackMessage {

  /**
   * What Slack says when a message cannot hold any more. {@code invalid_blocks} covers both the
   * 50-block ceiling and a single block over its own text limit; {@code msg_too_long} is the whole
   * message. One situation — the reply is full — and one remedy, which is {@link
   * #continueInNewMessage()}.
   */
  private static final List<String> FULL = List.of("invalid_blocks", "msg_too_long", "block_limit");

  /** Slack's own ceiling, which is worth staying under rather than being told about. */
  public static final int MAX_BLOCKS = 50;

  private final MethodsClient slack;

  @Getter private final String channelId;

  /**
   * The message being written to, which is not necessarily the one this started as: see {@link
   * #continueInNewMessage()}. Read on every call, so a rollover reaches everything holding this
   * object without any of them being told.
   */
  @Getter private volatile String ts;

  /**
   * How many messages the run has been through, which is what a writer compares against to notice
   * that the message it had been filling is not the one being written to any more. Bumped last,
   * once the new message is the one this object writes to.
   */
  @Getter private volatile long generation;

  /** The thread every continuation is posted into, so a long turn stays one conversation. */
  private final String threadTs;

  private final Duration streamInterval;

  private final int streamCharacters;

  private final ScheduledExecutorService flushes;

  private final ExecutorService writes;

  /** Somewhere for the run to carry on writing once a message is full. */
  public interface Continuation {
    /** The timestamp of a fresh message in the same thread, or null if one could not be posted. */
    String newMessage(String fullTs);
  }

  private volatile Continuation continuation;

  /** The newest state nothing has written yet, or null when the queue is empty. */
  private List<LayoutBlock> pending;

  private String pendingText;

  /** Set once a waiter is behind the queued write, which makes it urgent and unsupersedable. */
  private CompletableFuture<Void> pendingWaiter;

  private boolean draining;

  private boolean finished;

  /** When the last call *returned*, not when it was made — see {@link #dueNow()}. */
  private long lastWriteEndedAt;

  private ScheduledFuture<?> scheduledFlush;

  /**
   * Whether anything has been written since the last rollover, which is what tells a message that
   * filled up from a single write that is bigger than any message.
   */
  private final AtomicBoolean wroteSinceContinuation = new AtomicBoolean(true);

  public SlackMessage(
      final MethodsClient slack,
      final String channelId,
      final String ts,
      final String threadTs,
      final Duration streamInterval,
      final int streamCharacters,
      final ScheduledExecutorService flushes,
      final ExecutorService writes) {
    this.slack = slack;
    this.channelId = channelId;
    this.ts = ts;
    this.threadTs = threadTs;
    this.streamInterval = streamInterval == null ? Duration.ZERO : streamInterval;
    this.streamCharacters = streamCharacters;
    this.flushes = flushes;
    this.writes = writes;
    this.lastWriteEndedAt = 0L;
  }

  public void continuation(final Continuation continuation) {
    this.continuation = continuation;
  }

  /**
   * Queues {@code blocks} as the reply's whole content, replacing anything queued and not yet sent.
   *
   * @param fallback the notification text, which is what a phone shows and what a client that
   *     cannot render blocks falls back to
   */
  public void stream(final List<LayoutBlock> blocks, final String fallback) {
    enqueue(blocks, fallback, false);
  }

  /**
   * Queues {@code blocks} and waits for them to land.
   *
   * <p>The few writes whose caller has to know pay a round trip for it — finishing the reply, and
   * anything that has to be on the message before something else can refer to it. They happen once
   * per run rather than once per chunk, which is what makes that affordable.
   */
  public void await(final List<LayoutBlock> blocks, final String fallback) {
    final var waiter = enqueue(blocks, fallback, true);
    if (waiter == null) {
      return;
    }
    try {
      waiter.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.warn("Waiting on a write to {} did not complete", ts, e);
    }
  }

  /** Marks the reply finished, so the last write goes out whatever the clock says. */
  public void finish(final List<LayoutBlock> blocks, final String fallback) {
    synchronized (this) {
      finished = true;
    }
    await(blocks, fallback);
  }

  private synchronized CompletableFuture<Void> enqueue(
      final List<LayoutBlock> blocks, final String fallback, final boolean urgent) {
    // Superseded rather than queued behind: what arrives is the whole state, so the write already
    // waiting says strictly less than this one does. The exception is a write somebody is waiting
    // on, which has to land as itself before its caller is released.
    if (pendingWaiter != null && !pendingWaiter.isDone()) {
      // Somebody is already waiting. Their write still carries everything, since this one's state
      // is what the render produced most recently, so replace the content and keep the waiter.
      pending = blocks;
      pendingText = fallback;
      return pendingWaiter;
    }
    pending = blocks;
    pendingText = fallback;
    if (urgent) {
      pendingWaiter = new CompletableFuture<>();
    }
    final var waiter = pendingWaiter;
    pump();
    return waiter;
  }

  /** Hands the queue to a worker, unless one already has it. */
  private synchronized void pump() {
    if (draining || pending == null) {
      return;
    }
    if (!dueNow()) {
      scheduleFlush();
      return;
    }
    draining = true;
    try {
      writes.execute(this::drain);
    } catch (RejectedExecutionException e) {
      // Shutting down. Drained on this thread instead, because a caller waiting on this write would
      // otherwise wait for ever.
      draining = false;
      drain();
    }
  }

  /**
   * Whether the write waiting may go out now.
   *
   * <p>Measured from when the last call <em>returned</em> rather than when it was made: a slow
   * channel would otherwise be the one written to most often, which is exactly backwards.
   */
  private synchronized boolean dueNow() {
    if (finished || (pendingWaiter != null && !pendingWaiter.isDone())) {
      return true;
    }
    if (lastWriteEndedAt == 0L) {
      // The first thing the reply says appears at once. A run that opens with a pause looks broken.
      return true;
    }
    if (streamInterval.isZero()) {
      return true;
    }
    if (streamCharacters > 0 && queuedCharacters() >= streamCharacters) {
      return true;
    }
    return System.currentTimeMillis() - lastWriteEndedAt >= streamInterval.toMillis();
  }

  private int queuedCharacters() {
    return pendingText == null ? 0 : pendingText.length();
  }

  private synchronized void scheduleFlush() {
    if (scheduledFlush != null && !scheduledFlush.isDone()) {
      return;
    }
    final var delay = Math.max(1L, streamInterval.toMillis());
    try {
      scheduledFlush = flushes.schedule(this::pump, delay, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // The clock is gone, so nothing will come back for this. Sent now rather than lost.
      log.debug("Card clock is shutting down; writing {} immediately", ts);
      drain();
    }
  }

  private void drain() {
    while (true) {
      final List<LayoutBlock> blocks;
      final String fallback;
      final CompletableFuture<Void> waiter;
      synchronized (this) {
        if (pending == null) {
          draining = false;
          return;
        }
        blocks = pending;
        fallback = pendingText;
        waiter = pendingWaiter;
        pending = null;
        pendingWaiter = null;
      }
      try {
        write(blocks, fallback);
      } catch (Exception e) {
        log.warn("Could not write the reply in {} at {}", channelId, ts, e);
      } finally {
        synchronized (this) {
          lastWriteEndedAt = System.currentTimeMillis();
        }
        if (waiter != null) {
          waiter.complete(null);
        }
      }
      synchronized (this) {
        if (pending == null) {
          draining = false;
          return;
        }
        if (!dueNow()) {
          draining = false;
          scheduleFlush();
          return;
        }
      }
    }
  }

  private void write(final List<LayoutBlock> blocks, final String fallback) throws Exception {
    final var target = ts;
    final var response =
        slack.chatUpdate(r -> r.channel(channelId).ts(target).blocks(blocks).text(fallback));
    if (response.isOk()) {
      wroteSinceContinuation.set(true);
      return;
    }
    final var error = response.getError();
    if ("ratelimited".equals(error)) {
      // Slack names how long to wait, and waiting is the whole remedy: the write still carries the
      // newest state when it goes out. A virtual thread, so this costs a stack rather than a
      // worker.
      final var retryAfter = retryAfterSeconds(response.getHttpResponseHeaders());
      log.debug("Rate limited writing {}; waiting {}s", target, retryAfter);
      Thread.sleep(Duration.ofSeconds(retryAfter));
      final var retry =
          slack.chatUpdate(r -> r.channel(channelId).ts(target).blocks(blocks).text(fallback));
      if (retry.isOk()) {
        wroteSinceContinuation.set(true);
        return;
      }
      log.warn(
          "Still refused after waiting out the rate limit on {}: {}", target, retry.getError());
      return;
    }
    if (FULL.contains(error)) {
      if (continueInNewMessage()) {
        write(blocks, fallback);
      }
      return;
    }
    log.warn("Slack refused a write to {} in {}: {}", target, channelId, error);
  }

  private static long retryAfterSeconds(final java.util.Map<String, List<String>> headers) {
    if (headers == null) {
      return 1L;
    }
    for (final var entry : headers.entrySet()) {
      if ("retry-after".equalsIgnoreCase(entry.getKey())
          && entry.getValue() != null
          && !entry.getValue().isEmpty()) {
        try {
          return Math.max(1L, Long.parseLong(entry.getValue().get(0).trim()));
        } catch (NumberFormatException ignored) {
          return 1L;
        }
      }
    }
    return 1L;
  }

  /**
   * Finishes the message that filled up and carries on in a fresh one in the same thread.
   *
   * <p>Nothing is copied over. What made the message full is exactly the content that would be
   * copied, so a continuation arriving with it would be full before the run wrote a word — the
   * updater starts the new message from what it has said <em>since</em>, which is the only thing
   * that fits.
   *
   * <p>The new message is posted before the old one is closed. Closing first would leave a run
   * whose workspace refused the second message with nowhere to write at all.
   *
   * @return whether there is somewhere to carry on
   */
  public synchronized boolean continueInNewMessage() {
    if (continuation == null) {
      log.warn("The reply in {} is full and there is nowhere to continue", channelId);
      return false;
    }
    if (!wroteSinceContinuation.get()) {
      // Nothing has landed since the last rollover, so what is being written is bigger than a
      // message and every message after this would fill up on the same write. Continuing would post
      // one message per attempt.
      log.warn(
          "A single write is larger than a Slack message allows; not continuing again in {}",
          channelId);
      return false;
    }
    final var full = ts;
    final var next = continuation.newMessage(full);
    if (next == null) {
      return false;
    }
    ts = next;
    wroteSinceContinuation.set(false);
    generation++;
    log.info("Reply in {} filled up at {}, continuing in {}", channelId, full, next);
    return true;
  }

  /** The thread a continuation belongs to. */
  public String threadTs() {
    return threadTs;
  }
}
