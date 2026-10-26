package me.kezhenxu94.springagent.integration.telegram.config;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Stops taking updates first thing on shutdown, before the agent starts waiting out the runs
 * already going.
 *
 * <p>{@code SlackSocketConnection}'s counterpart, and ordered the same way for the same reason:
 * {@code SpringAgent#onShutdown} waits for up to {@code app.shutdown.in-flight-wait-timeout}, and a
 * surface still taking messages for all of that wait takes them only to leave them. A poll stopped
 * first leaves them with Telegram, unconfirmed, for the replica that replaces this one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramPollingConnection {

  private final TelegramUpdatePoller poller;

  private volatile boolean stopping;

  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ContextClosedEvent.class)
  public void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    log.info(
        "Shutdown: no longer polling Telegram, so another replica is handed what arrives next");
    try {
      poller.stop();
    } catch (Exception e) {
      log.warn("Could not stop polling Telegram", e);
    }
  }

  @PreDestroy
  public void close() {
    stop();
  }
}
