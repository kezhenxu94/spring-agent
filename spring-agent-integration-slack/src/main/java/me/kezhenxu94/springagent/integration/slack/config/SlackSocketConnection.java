package me.kezhenxu94.springagent.integration.slack.config;

import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lets go of the Socket Mode connection first thing on shutdown, so that a replica being replaced
 * stops being sent messages it can no longer answer.
 *
 * <p>Closing it as the bean's {@code destroyMethod} was not enough, and this is a shutdown-ordering
 * problem rather than anything about Slack. Singletons are destroyed only after every {@code
 * ContextClosedEvent} listener has returned, and one of those is {@link SpringAgent#onShutdown()},
 * which waits out the runs already going for as long as {@code app.shutdown.in-flight-wait-timeout}
 * allows. So the connection used to stay open for that whole wait, and for all of it Slack kept
 * delivering to a replica whose {@code accepting()} was already false, which fails the
 * acknowledgement of every message rather than answering it. Ordered {@link
 * Ordered#HIGHEST_PRECEDENCE} against that listener's {@link Ordered#LOWEST_PRECEDENCE}, so the
 * connection is gone before the wait begins and Slack has somewhere else to deliver.
 *
 * <p>Auto-reconnect is switched off before closing, not after: {@code SocketModeApp.stop()}
 * disconnects while leaving it on, and the client's session monitor exists precisely to pull a
 * dropped connection back up. That is the behaviour wanted every other minute of the process's
 * life, which is why nothing else here supervises the connection — Bolt already does — and why it
 * has to be revoked exactly once, here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackSocketConnection {

  private final SocketModeApp socketModeApp;

  private volatile boolean stopping;

  /**
   * Idempotent: reached from the event, and from {@link #close()} for a context that closes without
   * publishing one.
   */
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ContextClosedEvent.class)
  public void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    log.info(
        "Shutdown: closing the Slack Socket Mode connection, so that another replica is sent what"
            + " arrives next");
    try {
      final var client = socketModeApp.getClient();
      if (client != null) {
        client.setAutoReconnectEnabled(false);
      }
      socketModeApp.close();
    } catch (Exception e) {
      log.warn("Could not close the Slack Socket Mode connection", e);
    }
  }

  @PreDestroy
  public void close() {
    stop();
  }
}
