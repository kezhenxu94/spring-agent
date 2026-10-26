package me.kezhenxu94.springagent.integration.telegram.config;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramApiException;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramMessageReceiveHandler;

/**
 * Takes updates off Telegram by long polling and hands each to the dispatcher, in order.
 *
 * <p><b>Long polling rather than a webhook</b>, for the reason the Slack surface uses Socket Mode:
 * no public URL, no certificate and no secret header to check. What it costs is the one thing
 * Socket Mode does not cost — <b>only one process may poll a bot</b>. A second one gets {@code 409
 * Conflict} on every poll, and the two take turns at the updates; so a deployment of this surface
 * is one replica, and a rolling update briefly has two, which this logs and rides out rather than
 * fails over.
 *
 * <p><b>An update is confirmed only once it has been handled.</b> Asking for {@code offset} is what
 * tells Telegram to forget everything before it, so the offset moves past an update after its
 * handler returns and not before. A process that dies in between is sent the update again, which
 * {@code ProcessedMessageRepo#claim} already exists to absorb. And while shutting down, an update
 * that arrived is left where it is rather than refused: the poll stops without confirming it, and
 * the next process to poll is handed it.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramUpdatePoller {

  /** What this module handles, so Telegram does not send what it would only drop. */
  static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query");

  /** The longest a failing poll waits before trying again. */
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final TelegramBotApi telegram;
  private final TelegramUpdateDispatcher dispatcher;
  private final SpringAgent springAgent;
  private final Duration pollTimeout;

  private volatile boolean stopping;

  private volatile Thread thread;

  /** The next update wanted, which is one past the last one handled. */
  private volatile long offset;

  /** Starts polling on a thread of its own, returning at once. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = Thread.ofPlatform().daemon().name("telegram-poller").start(this::run);
    log.info("Polling Telegram for updates");
  }

  /**
   * Stops taking updates. What has been handled is confirmed on the way out, so the next process is
   * not handed it again; whatever was fetched and not handled is left for it.
   */
  public synchronized void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    final var running = thread;
    if (running != null) {
      running.interrupt();
      try {
        running.join(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      // A poll that confirms and waits for nothing: timeout zero. Whatever it fetches is not
      // confirmed by it, so nothing is lost by asking.
      if (offset > 0) {
        telegram.getUpdates(offset, Duration.ZERO, ALLOWED_UPDATES);
      }
    } catch (Exception e) {
      log.debug("Could not confirm the last updates on the way out: {}", e.getMessage());
    }
  }

  private void run() {
    var backoff = Duration.ofSeconds(1);
    while (!stopping) {
      if (!springAgent.accepting()) {
        // Shutting down, and every update fetched now would be one refused.
        return;
      }
      try {
        final var updates = telegram.getUpdates(offset, pollTimeout, ALLOWED_UPDATES);
        backoff = Duration.ofSeconds(1);
        for (final var update : updates) {
          if (stopping) {
            return;
          }
          try {
            dispatcher.dispatch(update);
          } catch (TelegramMessageReceiveHandler.NotAccepting e) {
            // Left unconfirmed, for whichever process polls next.
            return;
          } catch (Exception e) {
            log.error("Failed to handle Telegram update {}", update.updateId(), e);
          }
          offset = update.updateId() + 1;
        }
      } catch (TelegramApiException e) {
        if (stopping) {
          return;
        }
        if (e.conflict()) {
          log.error(
              "Another process is polling this bot ({}). Only one may: run a single replica of"
                  + " this surface, and check nothing else uses TELEGRAM_BOT_TOKEN.",
              e.description());
        } else {
          log.warn("Polling Telegram failed; retrying in {}: {}", backoff, e.getMessage());
        }
        if (!sleep(backoff)) {
          return;
        }
        backoff =
            backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0
                ? MAX_BACKOFF
                : backoff.multipliedBy(2);
      } catch (Exception e) {
        if (stopping) {
          return;
        }
        log.error("Polling Telegram failed unexpectedly; retrying in {}", backoff, e);
        if (!sleep(backoff)) {
          return;
        }
      }
    }
  }

  private static boolean sleep(final Duration duration) {
    try {
      Thread.sleep(duration);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
