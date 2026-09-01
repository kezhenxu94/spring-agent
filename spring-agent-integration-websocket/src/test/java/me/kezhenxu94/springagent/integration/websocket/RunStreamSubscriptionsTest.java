package me.kezhenxu94.springagent.integration.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.run.RunEvent;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournals;
import me.kezhenxu94.springagent.integration.websocket.web.RunStreamSubscriptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a browser is allowed to watch, and what it is told first.
 *
 * <p>Two things are pinned here and neither is visible in the UI when it breaks. The first is the
 * ownership check: a subscription names a run id, so without it any logged-in person could read
 * anybody's run — every tool call, every file path, everything the model said. The second is the
 * {@code replay} marker, which is the only thing that tells the page where a backlog ends now that
 * there is no SSE connection whose state it could infer that from.
 */
class RunStreamSubscriptionsTest {

  private static final String MINE = "ou_mine";
  private static final String THEIRS = "ou_theirs";

  /** Every frame the client would have received, in order. */
  private static final class Outbound implements MessageChannel {
    final List<Message<?>> sent = new ArrayList<>();

    @Override
    public boolean send(final Message<?> message) {
      sent.add(message);
      return true;
    }

    @Override
    public boolean send(final Message<?> message, final long timeout) {
      return send(message);
    }

    List<String> types(final JsonMapper om) {
      return sent.stream()
          .map(it -> om.readValue((byte[]) it.getPayload(), Map.class).get("type").toString())
          .toList();
    }
  }

  private final JsonMapper om = JsonMapper.builder().build();
  private final Outbound outbound = new Outbound();
  private final RunJournals journals =
      new RunJournals(new WebProperties(null, null, null, null, null));
  private final RunStreamSubscriptions subscriptions =
      new RunStreamSubscriptions(journals, om, outbound);

  /** A SUBSCRIBE frame's headers, as the annotated handler would be given them. */
  private static StompHeaderAccessor subscribe(
      final String userId, final String requestId, final String from) {
    final var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    headers.setSessionId("session-1");
    headers.setSubscriptionId("sub-1");
    headers.setDestination("/app/runs/" + requestId);
    if (from != null) {
      headers.setNativeHeader("from", from);
    }
    headers.setUser(
        new TestingAuthenticationToken(
            new DefaultOAuth2User(List.of(), Map.of("open_id", userId), "open_id"), "n/a"));
    return headers;
  }

  @Test
  @DisplayName("somebody else's run is indistinguishable from one that never existed")
  void aRunThatIsNotYoursIsGone() {
    final var journal = journals.open("run-1", "conversation-1", MINE);
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "secret")));

    subscriptions.watch("run-1", subscribe(THEIRS, "run-1", "0"));

    // The same single answer an id nobody ever used would get, and nothing of the run itself.
    assertThat(outbound.types(om)).containsExactly(RunEvent.GONE);
  }

  @Test
  @DisplayName("an id with no journal is gone too")
  void anUnknownRunIsGone() {
    subscriptions.watch("run-unknown", subscribe(MINE, "run-unknown", "0"));
    assertThat(outbound.types(om)).containsExactly(RunEvent.GONE);
  }

  @Test
  @DisplayName("the backlog is preceded by where it ends, and live events follow past it")
  void ownRunReplaysThenStreams() {
    final var journal = journals.open("run-1", "conversation-1", MINE);
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "one")));
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "two")));

    subscriptions.watch("run-1", subscribe(MINE, "run-1", "0"));

    assertThat(outbound.types(om))
        .containsExactly(RunEvent.REPLAY, RunEvent.CONTENT, RunEvent.CONTENT);
    // Two events existed, so anything numbered above two is happening now rather than being caught
    // up on. This is the whole of what the page has to go on.
    assertThat(om.readValue((byte[]) outbound.sent.getFirst().getPayload(), Map.class))
        .extracting("data")
        .isEqualTo(Map.of("through", 2));

    // Still attached: what the run says next reaches the same subscription.
    journal.append(RunEvent.of(RunEvent.FINISHED, Map.of("outcome", "COMPLETED")));
    assertThat(outbound.types(om)).endsWith(List.of(RunEvent.FINISHED).toArray(String[]::new));

    // Addressed to the one session that asked, which is what makes replay per-subscriber safe.
    final var last = StompHeaderAccessor.wrap(outbound.sent.getLast());
    assertThat(last.getSessionId()).isEqualTo("session-1");
    assertThat(last.getSubscriptionId()).isEqualTo("sub-1");
  }

  @Test
  @DisplayName("a cursor is honoured, and an unreadable one means everything")
  void resumesFromTheCursor() {
    final var journal = journals.open("run-1", "conversation-1", MINE);
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "one")));
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "two")));

    subscriptions.watch("run-1", subscribe(MINE, "run-1", "1"));
    assertThat(outbound.types(om)).containsExactly(RunEvent.REPLAY, RunEvent.CONTENT);

    outbound.sent.clear();
    subscriptions.watch("run-1", subscribe(MINE, "run-1", "not a number"));
    assertThat(outbound.types(om))
        .containsExactly(RunEvent.REPLAY, RunEvent.CONTENT, RunEvent.CONTENT);
  }

  @Test
  @DisplayName("unsubscribing detaches, and says nothing to the run about it")
  void unsubscribingDetaches() {
    final var journal = journals.open("run-1", "conversation-1", MINE);
    subscriptions.watch("run-1", subscribe(MINE, "run-1", "0"));

    final var unsubscribe = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
    unsubscribe.setSessionId("session-1");
    unsubscribe.setSubscriptionId("sub-1");
    subscriptions.onUnsubscribe(
        new org.springframework.web.socket.messaging.SessionUnsubscribeEvent(
            this,
            org.springframework.messaging.support.MessageBuilder.createMessage(
                new byte[0], unsubscribe.getMessageHeaders())));

    outbound.sent.clear();
    journal.append(RunEvent.of(RunEvent.CONTENT, Map.of("text", "nobody is listening")));
    assertThat(outbound.sent).isEmpty();
    // The run carried on regardless, which is the point: a closed tab is not an instruction.
    assertThat(journal.size()).isEqualTo(1);
  }
}
