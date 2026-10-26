package me.kezhenxu94.springagent.integration.telegram.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramApiException;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.InlineKeyboardMarkup;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;

/**
 * One reply, as the thing that writes to it: the chat, the messages it has become, and the queue
 * every change waits in.
 *
 * <p><b>Nothing a run says costs it a round trip</b>, and a queued write is replaced by the next
 * rather than queued behind it — the same arrangement as {@code SlackMessage}, for the same
 * reasons, and the same answer to Telegram's rate limit: an interval between edits that is a floor,
 * and a 429's {@code retry_after} slept out on a virtual thread rather than the write dropped.
 *
 * <p><b>A reply is as many messages as it needs.</b> Telegram holds 4096 characters in a message
 * and a turn holds whatever it holds, so what is written is the whole reply and it is cut into
 * pages here: every page but the last is a message that has filled up, the last carries the
 * buttons, and a page that grows past the limit pushes the rest onto a new message. Each write
 * edits only the pages whose text changed, so a page that filled up is written once more at most
 * and then left alone. Nothing is lost at a cut, which is the thing a fixed rollover cannot
 * promise: the page boundaries are recomputed from the whole text on every write.
 */
@Slf4j
public class TelegramMessage {

  /** Under Telegram's 4096, leaving room for the line a page ends on not being where it is cut. */
  static final int PAGE_LIMIT = 4000;

  private final TelegramBotApi telegram;

  @Getter private final String chatId;

  private final Integer threadId;

  /** The message the reply answers, which every page is posted as a reply to. */
  private final Integer replyTo;

  private final Duration streamInterval;
  private final ScheduledExecutorService flushes;
  private final ExecutorService writes;

  /** Every message the reply has become, first to last. Read from other threads. */
  private final List<Integer> ids = new CopyOnWriteArrayList<>();

  /** What each of those messages last showed, so an unchanged page is not edited. */
  private final List<TelegramText> written = new ArrayList<>();

  private final List<InlineKeyboardMarkup> writtenKeyboards = new ArrayList<>();

  /** The newest state nothing has written yet, or null when the queue is empty. */
  private TelegramText pending;

  private InlineKeyboardMarkup pendingKeyboard;

  /** Set once a waiter is behind the queued write, which makes it urgent and unsupersedable. */
  private CompletableFuture<Void> pendingWaiter;

  private boolean draining;

  private boolean finished;

  /** When the last call returned, not when it was made — see {@code SlackMessage#dueNow}. */
  private long lastWriteEndedAt;

  private ScheduledFuture<?> scheduledFlush;

  /**
   * @param firstId the message already posted for this reply, which the first page is written to
   * @param shown what that message already says
   */
  public TelegramMessage(
      final TelegramBotApi telegram,
      final String chatId,
      final Integer threadId,
      final Integer replyTo,
      final int firstId,
      final TelegramText shown,
      final Duration streamInterval,
      final ScheduledExecutorService flushes,
      final ExecutorService writes) {
    this.telegram = telegram;
    this.chatId = chatId;
    this.threadId = threadId;
    this.replyTo = replyTo;
    this.streamInterval = streamInterval == null ? Duration.ZERO : streamInterval;
    this.flushes = flushes;
    this.writes = writes;
    ids.add(firstId);
    written.add(shown);
    writtenKeyboards.add(InlineKeyboardMarkup.none());
  }

  /**
   * The message the buttons are on, which is the last one the reply has become. A question form is
   * answered from there, so this is what a pending question records.
   */
  public int lastId() {
    return ids.getLast();
  }

  /** Every message the reply has become. */
  public List<Integer> ids() {
    return List.copyOf(ids);
  }

  /** Queues {@code text} as the reply's whole content, replacing anything not yet sent. */
  public void stream(final TelegramText text, final InlineKeyboardMarkup keyboard) {
    enqueue(text, keyboard, false);
  }

  /** Queues {@code text} and waits for it to land — see {@code SlackMessage#await}. */
  public void await(final TelegramText text, final InlineKeyboardMarkup keyboard) {
    final var waiter = enqueue(text, keyboard, true);
    if (waiter == null) {
      return;
    }
    try {
      waiter.get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.warn("Waiting on a write to {} in {} did not complete", ids, chatId, e);
    }
  }

  /** Marks the reply finished, so the last write goes out whatever the clock says. */
  public void finish(final TelegramText text, final InlineKeyboardMarkup keyboard) {
    synchronized (this) {
      finished = true;
    }
    await(text, keyboard);
  }

  private synchronized CompletableFuture<Void> enqueue(
      final TelegramText text, final InlineKeyboardMarkup keyboard, final boolean urgent) {
    pending = text;
    pendingKeyboard = keyboard == null ? InlineKeyboardMarkup.none() : keyboard;
    if (pendingWaiter != null && !pendingWaiter.isDone()) {
      // Somebody is already waiting, and their write now carries this state too.
      return pendingWaiter;
    }
    if (urgent) {
      pendingWaiter = new CompletableFuture<>();
    }
    final var waiter = pendingWaiter;
    pump();
    return waiter;
  }

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
      // Shutting down. Drained here instead, or a caller waiting on this write waits for ever.
      draining = false;
      drain();
    }
  }

  private synchronized boolean dueNow() {
    if (finished || (pendingWaiter != null && !pendingWaiter.isDone())) {
      return true;
    }
    if (lastWriteEndedAt == 0L || streamInterval.isZero()) {
      return true;
    }
    return System.currentTimeMillis() - lastWriteEndedAt >= streamInterval.toMillis();
  }

  private synchronized void scheduleFlush() {
    if (scheduledFlush != null && !scheduledFlush.isDone()) {
      return;
    }
    final var delay = Math.max(1L, streamInterval.toMillis());
    try {
      scheduledFlush = flushes.schedule(this::pump, delay, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      log.debug("Message clock is shutting down; writing {} immediately", ids);
      drain();
    }
  }

  private void drain() {
    while (true) {
      final TelegramText text;
      final InlineKeyboardMarkup keyboard;
      final CompletableFuture<Void> waiter;
      synchronized (this) {
        if (pending == null) {
          draining = false;
          return;
        }
        text = pending;
        keyboard = pendingKeyboard;
        waiter = pendingWaiter;
        pending = null;
        pendingWaiter = null;
      }
      try {
        write(text, keyboard);
      } catch (Exception e) {
        log.warn("Could not write the reply in {}", chatId, e);
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

  /** Makes the messages show {@code text}, editing what changed and posting what is new. */
  private void write(final TelegramText text, final InlineKeyboardMarkup keyboard) {
    final var pages = text.pages(PAGE_LIMIT);
    for (var i = 0; i < pages.size(); i++) {
      final var page = pages.get(i).isBlank() ? TelegramText.plain("…") : pages.get(i);
      final var buttons = i == pages.size() - 1 ? keyboard : InlineKeyboardMarkup.none();
      if (i < ids.size()) {
        if (page.equals(written.get(i)) && Objects.equals(buttons, writtenKeyboards.get(i))) {
          continue;
        }
        final var id = ids.get(i);
        if (attempt(() -> telegram.editMessageText(chatId, id, page, buttons), "edit " + id)) {
          written.set(i, page);
          writtenKeyboards.set(i, buttons);
        }
        continue;
      }
      final var posted = new Integer[1];
      if (!attempt(
          () ->
              posted[0] =
                  telegram
                      .sendMessage(chatId, threadId, page, replyTo, nonEmpty(buttons))
                      .messageId(),
          "continue")) {
        // Nowhere to put the rest, and every page after this one would fail the same way.
        return;
      }
      ids.add(posted[0]);
      written.add(page);
      writtenKeyboards.add(buttons);
      log.info("Reply in {} continues in message {}", chatId, posted[0]);
    }
    // Fewer pages than messages only where the text got shorter, which a reply only does when it
    // is replaced — a failure shown in place of an answer. What is past the end goes.
    while (ids.size() > pages.size()) {
      final var id = ids.removeLast();
      written.removeLast();
      writtenKeyboards.removeLast();
      attempt(() -> telegram.deleteMessage(chatId, id), "delete " + id);
    }
  }

  private static InlineKeyboardMarkup nonEmpty(final InlineKeyboardMarkup keyboard) {
    return keyboard == null || keyboard.isEmpty() ? null : keyboard;
  }

  /**
   * Makes one call, sleeping out a rate limit once, and says whether what it was meant to change is
   * now so. An edit Telegram calls a no-op is: it already showed that.
   */
  private boolean attempt(final Runnable call, final String what) {
    for (var attempt = 0; attempt < 2; attempt++) {
      try {
        call.run();
        return true;
      } catch (TelegramApiException e) {
        if (e.notModified()) {
          return true;
        }
        if (e.rateLimited() && attempt == 0) {
          final var wait = e.retryAfter() == null ? Duration.ofSeconds(1) : e.retryAfter();
          log.debug("Rate limited writing {} in {}; waiting {}", what, chatId, wait);
          try {
            Thread.sleep(wait);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
          }
          continue;
        }
        log.warn("Telegram refused to {} in {}: {}", what, chatId, e.getMessage());
        return false;
      }
    }
    return false;
  }
}
