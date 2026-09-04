package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.okhttp.WebSocket;
import com.lark.oapi.ws.Client;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;

/**
 * The supervision {@link FeishuLongConnection} exists for, driven a check at a time rather than by
 * waiting for its own clock.
 *
 * <p>The clients here are mocks with their {@code conn} field written directly, which is the same
 * field the class under test reads — there is no other way to say "this client has a connection",
 * for exactly the reason that class has to look at the field in the first place.
 */
class FeishuLongConnectionTest {

  private final List<ReadinessState> readiness = new ArrayList<>();
  private final List<Client> handedOut = new ArrayList<>();
  private final List<Client> released = new ArrayList<>();
  private final List<ExecutorService> executors = new ArrayList<>();

  /**
   * Read back by {@link #newClient()}, so a test can hand out a dead client on the next attempt.
   */
  private boolean nextClientConnected = true;

  private final FeishuLongConnection connection =
      new FeishuLongConnection(
          new FeishuProperties(
              null, null, null, "cli_1", "secret", null, null, null, null, null, null),
          mock(EventDispatcher.class),
          event -> {
            if (event instanceof AvailabilityChangeEvent<?> change
                && change.getState() instanceof ReadinessState state) {
              readiness.add(state);
            }
          }) {

        @Override
        Client connect() {
          return newClient();
        }

        @Override
        void release(final Client client) {
          released.add(client);
          super.release(client);
        }
      };

  @AfterEach
  void letGo() {
    connection.stop();
    executors.forEach(ExecutorService::shutdownNow);
  }

  /**
   * A client the class under test can read like a real one: a {@code conn} where it looks for the
   * connection, and a live {@code executor} so that releasing one makes the same call it would
   * against the SDK's own.
   */
  private Client newClient() {
    final var client = mock(Client.class);
    final var executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    set(client, "executor", executor);
    if (nextClientConnected) {
      set(client, "conn", mock(WebSocket.class));
    }
    handedOut.add(client);
    return client;
  }

  private Client current() {
    return handedOut.get(handedOut.size() - 1);
  }

  private void dropTheConnection() {
    set(current(), "conn", null);
  }

  private static void set(final Client client, final String name, final Object value) {
    try {
      final var field = Client.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(client, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void saysNothingWhileTheConnectionIsThere() {
    connection.start();

    connection.check();
    connection.check();

    assertThat(connection.connected()).isTrue();
    assertThat(handedOut).hasSize(1);
    assertThat(readiness).isEmpty();
  }

  @Test
  void refusesTrafficAsSoonAsTheConnectionIsGone() {
    connection.start();
    dropTheConnection();

    connection.check();

    // Said on the first check, and the connection not rebuilt on it: the handshake is asynchronous,
    // so one look with no connection is also what a client that has just started looks like.
    assertThat(readiness).containsExactly(ReadinessState.REFUSING_TRAFFIC);
    assertThat(handedOut).hasSize(1);
    assertThat(released).isEmpty();
  }

  @Test
  void reopensTheConnectionOnceItHasBeenGoneForTwoChecks() {
    connection.start();
    final var dead = current();
    dropTheConnection();

    connection.check();
    connection.check();

    assertThat(handedOut).hasSize(2);
    assertThat(released).containsExactly(dead);
    assertThat(connection.connected()).isTrue();
    // The rebuild alone is not the recovery: the next check is what finds a connection and says so.
    assertThat(readiness).containsExactly(ReadinessState.REFUSING_TRAFFIC);

    connection.check();

    assertThat(readiness)
        .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
    assertThat(handedOut).hasSize(2);
  }

  @Test
  void keepsTryingWhileReopeningFails() {
    connection.start();
    dropTheConnection();
    nextClientConnected = false;

    connection.check();
    connection.check();
    connection.check();
    connection.check();

    assertThat(handedOut).hasSize(3);
    // Once, however many attempts it takes. A probe that flapped while nothing had changed would be
    // a worse signal than one that stays down until there is something to report.
    assertThat(readiness).containsExactly(ReadinessState.REFUSING_TRAFFIC);
  }

  @Test
  void letsGoOfTheConnectionOnceAndChecksNothingAfterwards() {
    connection.start();
    final var live = current();

    connection.stop();
    connection.stop();

    assertThat(released).containsExactly(live);
    assertThat(connection.connected()).isFalse();

    connection.check();

    // No rebuild, and nothing said about readiness: the replica is on its way out, and reopening
    // the connection here is precisely the reconnect this class was written to stop.
    assertThat(handedOut).hasSize(1);
    assertThat(readiness).isEmpty();
  }
}
