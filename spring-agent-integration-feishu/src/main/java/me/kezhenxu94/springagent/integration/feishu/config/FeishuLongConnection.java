package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * How this application hears from Feishu: one long connection, opened as this bean is created and
 * let go of first thing on shutdown.
 *
 * <p>Owning the connection here rather than declaring the SDK's {@code Client} as a bean with
 * {@code initMethod}/{@code destroyMethod} is what the three problems below need, and every one of
 * them showed up as messages nobody answered during a rolling update.
 *
 * <p><b>It is let go of before the drain, not after it.</b> A bean's {@code destroyMethod} runs
 * during singleton destruction, which is after every {@code ContextClosedEvent} listener has
 * returned — and one of those is {@link SpringAgent#onShutdown()}, which waits out the runs already
 * going for as long as {@code app.shutdown.in-flight-wait-timeout} allows. So a connection closed
 * at destruction stays open for the whole wait, and for all of it Feishu goes on delivering to a
 * replica whose {@code accepting()} is already false, which answers every message by throwing it
 * away. Closing it from the earliest-ordered shutdown listener instead hands the surface back to
 * whichever replica can still serve it, in the second the signal arrives.
 *
 * <p><b>It never reconnects on the way down.</b> The SDK's own reconnect is on by default and is
 * driven from its websocket listener: closing the connection makes {@code Listener.onClosed} call
 * {@code reconnect()}, which with the SDK's {@code reconnectCount = -1} retries for ever. A dying
 * replica therefore used to come back and go on competing for events until the JVM exited. So the
 * client is built with {@code autoReconnect(false)} and reconnecting is wholly this class's job —
 * which also means a rebuilt connection can never race a ghost the SDK is bringing back.
 *
 * <p><b>A connection that is not there is noticed.</b> {@code Client.start()} returns before the
 * websocket handshake finishes, and a handshake refused for good — the app's connection limit
 * reached, which is precisely what two replicas overlapping risks — reaches the SDK's listener as a
 * {@code ClientException} that it logs and nothing else. No retry, no exception on any thread this
 * application can see. The result was a replica that started cleanly, reported itself healthy and
 * heard nothing until somebody restarted it. {@link #check()} is what notices, says so on the
 * readiness probe, and gets the connection back.
 */
@Slf4j
@Component
public class FeishuLongConnection {

  /**
   * How often {@link #check()} runs. Long enough that a healthy deployment reads one field every
   * half minute and does nothing else, short enough that a connection lost while nobody was talking
   * to the bot is back before somebody is.
   */
  static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

  /**
   * How many checks in a row must find no connection before it is rebuilt. More than one because
   * there is a legitimate moment with no connection: the handshake is asynchronous, so a client
   * that has just been started has none yet, and rebuilding on the first look would tear down a
   * connection that was about to arrive — and every rebuild is another attempt at a connection slot
   * that may be limited.
   */
  static final int REBUILD_AFTER_DOWN_CHECKS = 2;

  /**
   * The connection itself, and the pool the SDK dispatches events on. Both are {@code protected}
   * members of {@code com.lark.oapi.ws.Client} (oapi-sdk 2.6.1) with no accessor, and the class's
   * only constructor is private, so a subclass cannot reach them either.
   *
   * <ul>
   *   <li>{@code conn} is the only thing that says whether there is a connection. The SDK exposes
   *       no state, no listener and no health of any kind, and "have we been sent anything lately"
   *       is not an answer — a quiet chat is the normal case.
   *   <li>{@code disconnect()} is how a connection is given up. It is what Spring used to call as
   *       the bean's destroy method, reflectively, for the same reason.
   *   <li>{@code executor} is a fixed pool of <em>non-daemon</em> threads, one of which runs a
   *       {@code while (true)} ping loop that {@code disconnect()} does not stop. Left running it
   *       keeps the JVM up after everything else has shut down, and one is leaked per rebuild.
   * </ul>
   *
   * <p>If a future SDK gives any of these a public equivalent, use it and delete this.
   */
  private static final Field CONN = field("conn");

  private static final Method DISCONNECT = method("disconnect");
  private static final Field EXECUTOR = field("executor");

  private final FeishuProperties properties;
  private final EventDispatcher eventDispatcher;
  private final ApplicationEventPublisher events;

  /**
   * One daemon thread. Daemon because a check is not a reason for a JVM to stay up, and one because
   * two checks at once would each see the other's half-finished rebuild.
   */
  private final ScheduledExecutorService supervisor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            final var thread = new Thread(runnable, "feishu-connection");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicReference<Client> client = new AtomicReference<>();

  /** Read by {@link #check()} on another thread, so that a check racing shutdown does nothing. */
  private volatile boolean stopping;

  /** Both written and read by the supervisor thread only, so neither needs to be volatile. */
  private int downChecks;

  private boolean refusingTraffic;

  public FeishuLongConnection(
      final FeishuProperties properties,
      final EventDispatcher eventDispatcher,
      final ApplicationEventPublisher events) {
    this.properties = properties;
    this.eventDispatcher = eventDispatcher;
    this.events = events;
  }

  @PostConstruct
  public void start() {
    // Not caught: a connection refused outright — bad credentials, or the wrong one of the two
    // products in app.feishu.base-url — is a deployment that cannot do its job, and failing to
    // start says so where a log line at WARN would be read by nobody.
    client.set(connect());
    supervisor.scheduleWithFixedDelay(
        this::check, CHECK_INTERVAL.toMillis(), CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Closes the connection, before anything else a shutdown does.
   *
   * <p>{@link Ordered#HIGHEST_PRECEDENCE} against {@link SpringAgent#onShutdown()}'s {@link
   * Ordered#LOWEST_PRECEDENCE}: see this class's own notes for what an unordered pair of these
   * costs. Idempotent, because it is reached both from the event and, for a context that never
   * publishes one, from {@link #close()}.
   */
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ContextClosedEvent.class)
  public void stop() {
    if (stopping) {
      return;
    }
    stopping = true;
    supervisor.shutdownNow();
    final var live = client.getAndSet(null);
    if (live != null) {
      log.info(
          "Shutdown: closing the Feishu long connection, so that another replica is sent what"
              + " arrives next");
      release(live);
    }
  }

  @PreDestroy
  public void close() {
    stop();
  }

  /** Whether there is a connection right now. For the tests, and for anything that asks later. */
  public boolean connected() {
    return connected(client.get());
  }

  /**
   * One look at the connection: says so on the readiness probe while it is gone, and rebuilds it
   * once it has been gone for {@link #REBUILD_AFTER_DOWN_CHECKS} looks.
   *
   * <p>Catches everything. A {@code scheduleWithFixedDelay} task that throws is never run again,
   * which would leave the supervision this class exists for silently switched off.
   */
  void check() {
    if (stopping) {
      return;
    }
    try {
      if (connected(client.get())) {
        downChecks = 0;
        if (refusingTraffic) {
          refusingTraffic = false;
          log.info("The Feishu long connection is back; this replica can be sent messages again");
          AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
        return;
      }

      if (!refusingTraffic) {
        refusingTraffic = true;
        // Said on the probe and not only in the log, because the whole failure being fixed here is
        // a replica that is healthy by every other measure and cannot hear a thing. A surface with
        // no connection is a surface, so this application is not ready in any useful sense.
        log.warn(
            "No Feishu long connection for app {}; nothing will reach this replica until it is"
                + " back",
            properties.appId());
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
      }

      if (++downChecks < REBUILD_AFTER_DOWN_CHECKS) {
        return;
      }
      downChecks = 0;
      log.info("Reopening the Feishu long connection");
      // A fresh client rather than start() on the old one: start() submits another ping loop into
      // the client's own pool every time it is called, and that pool is also what dispatches
      // incoming events — so retrying in place would leak one of a handful of threads per attempt
      // and end with a connection nobody is listening to.
      release(client.getAndSet(null));
      client.set(connect());
      if (stopping) {
        // A shutdown started while the connection above was being opened, so it found nothing to
        // close and this thread is the only one that can undo what it just did. Without this the
        // replica leaves a live connection behind and goes on being sent messages it cannot answer,
        // which is the whole failure this class exists to stop.
        release(client.getAndSet(null));
        return;
      }
      log.info("The Feishu long connection is open again");
    } catch (Throwable t) {
      log.error(
          "Could not reopen the Feishu long connection; trying again in {}", CHECK_INTERVAL, t);
    }
  }

  /** Built and started; overridable so that a test can have one without a socket. */
  Client connect() {
    final var connection =
        new Client.Builder(properties.appId(), properties.appSecret())
            .domain(properties.baseUrl().getUrl())
            .eventHandler(eventDispatcher)
            // See the class notes: the SDK's own reconnect is what brings a dying replica back.
            .autoReconnect(false)
            .build();
    connection.start();
    return connection;
  }

  boolean connected(final Client connection) {
    if (connection == null) {
      return false;
    }
    try {
      return CONN.get(connection) != null;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Cannot read the Feishu client's connection", e);
    }
  }

  /**
   * Gives up a client for good: the connection first, so nothing more is delivered, then the pool
   * whose ping loop would otherwise outlive it.
   */
  void release(final Client connection) {
    if (connection == null) {
      return;
    }
    try {
      DISCONNECT.invoke(connection);
    } catch (Exception e) {
      log.warn("Could not close the Feishu long connection", e);
    }
    try {
      ((ExecutorService) EXECUTOR.get(connection)).shutdownNow();
    } catch (Exception e) {
      log.warn("Could not stop the Feishu client's threads", e);
    }
  }

  private static Field field(final String name) {
    try {
      final var declared = Client.class.getDeclaredField(name);
      declared.setAccessible(true);
      return declared;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "The Feishu SDK's websocket client has no " + name + " field; see FeishuLongConnection",
          e);
    }
  }

  private static Method method(final String name) {
    try {
      final var declared = Client.class.getDeclaredMethod(name);
      declared.setAccessible(true);
      return declared;
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          "The Feishu SDK's websocket client has no " + name + "(); see FeishuLongConnection", e);
    }
  }
}
