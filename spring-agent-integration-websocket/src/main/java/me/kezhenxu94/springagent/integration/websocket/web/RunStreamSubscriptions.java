package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.run.RunEvent;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournal;
import me.kezhenxu94.springagent.integration.websocket.run.RunJournals;
import me.kezhenxu94.springagent.integration.websocket.security.WebUser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;
import tools.jackson.databind.json.JsonMapper;

/**
 * Watching a run happen.
 *
 * <p>A subscription is a reader of {@code RunJournal} and nothing more. Making one starts nothing;
 * dropping one stops nothing. Every path out of here — the browser navigating away, the connection
 * dying, an exception writing to the socket — does exactly one thing, which is to detach the
 * reader. That is the whole of what makes closing a tab safe.
 *
 * <p><b>Written straight to the client's own subscription rather than published to a topic.</b> The
 * reason is replay: a browser says how far it got and is sent the rest, which is a different stream
 * per subscriber even for the same run — two tabs on the same conversation resume from two
 * different places, and a topic would hand each of them the other's backlog. So this holds the
 * session and subscription id from the {@code SUBSCRIBE} frame and builds the {@code MESSAGE}
 * frames itself, which is also what makes the handover exact: {@link RunJournal#attach} replays and
 * registers under one lock, and the frames it produces go out on the same channel, in order, ahead
 * of anything the run appends next.
 *
 * <p><b>In order is not something the channel gives for free.</b> {@code clientOutboundChannel}
 * hands each frame to a pool thread of its own, so two frames sent back to back can reach the
 * socket either way round — and a client appends the stream's deltas as they arrive, so that is
 * text written out of order, mended only when the transcript is read back. Each session's frames
 * therefore go through an {@link OrderedMessageChannelDecorator}, Spring's own answer to this and
 * what its broker uses for {@code preservePublishOrder}: the next frame is handed over only once
 * the previous one has been written.
 *
 * <p>Resumption is explicit, unlike the SSE endpoint this replaced. There is no {@code
 * Last-Event-ID} in STOMP and no reconnect the protocol performs on the page's behalf, so the
 * cursor is a {@code from} header the page sets on every subscribe, including the ones its client
 * library makes after a dropped connection. {@link RunEvent#REPLAY} is sent first, carrying the
 * last sequence number that already existed, so the page can tell a backlog from what is happening
 * now — which over SSE it had been inferring from the connection's state.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RunStreamSubscriptions {

  private final RunJournals journals;
  private final JsonMapper om;

  /**
   * Where a frame addressed to one session goes. Written to directly, rather than through {@code
   * SimpMessagingTemplate}, because that publishes to the broker and the broker only forwards to
   * destinations it has seen a subscription for — a race this cannot afford, since the subscription
   * being answered is the one still being handled.
   */
  @Qualifier("clientOutboundChannel")
  private final MessageChannel clientOutbound;

  /** One per session: what keeps that session's frames in the order they were sent. */
  private final ConcurrentMap<String, MessageChannel> orderedBySession = new ConcurrentHashMap<>();

  private static final Log ORDERING_LOG = LogFactory.getLog(RunStreamSubscriptions.class);

  /** Keyed by session and subscription, which together are one browser watching one run. */
  private final ConcurrentMap<String, Attachment> attached = new ConcurrentHashMap<>();

  private record Attachment(RunJournal journal, RunJournal.Reader reader) {}

  @SubscribeMapping("/runs/{requestId}")
  public void watch(
      @DestinationVariable final String requestId, final StompHeaderAccessor headers) {

    final var user = user(headers);
    final var sessionId = headers.getSessionId();
    final var subscriptionId = headers.getSubscriptionId();
    final var destination = headers.getDestination();
    if (user == null || sessionId == null || subscriptionId == null || destination == null) {
      // Nothing to answer and nobody to answer it to. A subscribe with no session cannot happen
      // over STOMP; an unauthenticated one cannot happen behind the application's filter chain.
      log.warn("Refusing a subscribe to run {} that carried no session or no principal", requestId);
      return;
    }

    final var reader = new SubscriptionReader(sessionId, subscriptionId, destination, requestId);
    final var journal =
        journals.byRequestId(requestId).filter(it -> user.id().equals(it.userId())).orElse(null);
    if (journal == null) {
      // Gone, rather than never existed, and the same answer either way: a journal is evicted a
      // while after its run ends, and a run belonging to somebody else must not be distinguishable
      // from one that has been. The page's answer to both is the transcript, which is not lost.
      send(reader, RunEvent.of(RunEvent.GONE, Map.of()));
      return;
    }

    // Read before attaching, so the boundary is a number rather than a moment. Anything appended
    // between here and the attach below arrives in the same burst as the backlog but with a higher
    // sequence number, which is exactly what it is — live.
    send(reader, RunEvent.of(RunEvent.REPLAY, Map.of("through", journal.size())));

    detach(key(sessionId, subscriptionId));
    attached.put(key(sessionId, subscriptionId), new Attachment(journal, reader));
    try {
      journal.attach(reader, cursor(headers));
    } catch (final RuntimeException e) {
      detach(key(sessionId, subscriptionId));
      throw e;
    }
  }

  /** What the page already has. Absent, unreadable or negative all mean "everything". */
  private static long cursor(final StompHeaderAccessor headers) {
    final var from = headers.getFirstNativeHeader("from");
    if (from == null || from.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(from.trim()));
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Who is asking, from the principal the handshake established and never from the frame.
   *
   * <p>The websocket inherits it: Spring takes the user of a STOMP session from the HTTP request
   * that upgraded into it, which behind a filter chain is the logged-in person. So the same rule
   * every REST endpoint here follows applies to a subscription too — see {@link WebUser}.
   */
  private static WebUser user(final StompHeaderAccessor headers) {
    if (headers.getUser() instanceof Authentication authentication
        && authentication.getPrincipal() instanceof OAuth2User principal) {
      return WebUser.of(principal);
    }
    return null;
  }

  @EventListener
  public void onUnsubscribe(final SessionUnsubscribeEvent event) {
    final var headers = StompHeaderAccessor.wrap(event.getMessage());
    detach(key(headers.getSessionId(), headers.getSubscriptionId()));
  }

  @EventListener
  public void onDisconnect(final SessionDisconnectEvent event) {
    final var prefix = event.getSessionId() + '\u0000';
    attached.keySet().stream().filter(it -> it.startsWith(prefix)).toList().forEach(this::detach);
    orderedBySession.remove(event.getSessionId());
  }

  /**
   * The channel to write one session's frames to, in order. A channel that dispatches on a pool —
   * the real one — is wrapped; one that delivers on the caller's thread is already in order and is
   * used as it is.
   */
  private MessageChannel outbound(final String sessionId) {
    if (!(clientOutbound instanceof ExecutorSubscribableChannel pooled)
        || pooled.getExecutor() == null) {
      return clientOutbound;
    }
    return orderedBySession.computeIfAbsent(
        sessionId,
        id -> {
          // Idempotent: the interceptor that releases each session's next frame once the one
          // before it has been handled is added to the channel the first time only.
          OrderedMessageChannelDecorator.configureInterceptor(clientOutbound, true);
          return new OrderedMessageChannelDecorator(clientOutbound, ORDERING_LOG);
        });
  }

  private static String key(final String sessionId, final String subscriptionId) {
    return sessionId + '\u0000' + subscriptionId;
  }

  private void detach(final String key) {
    final var attachment = attached.remove(key);
    if (attachment != null) {
      attachment.journal().detach(attachment.reader());
    }
  }

  /**
   * A frame this class produces itself rather than one the journal produced.
   *
   * <p>Failure is swallowed here where the reader rethrows it, because there is nothing to drop
   * yet: a browser that has gone away between subscribing and being answered is not an error, and
   * letting it out of a {@code @SubscribeMapping} would put a STOMP {@code ERROR} frame on a
   * connection most clients respond to by closing — taking every other run the page is watching
   * with it.
   */
  private void send(final SubscriptionReader reader, final RunEvent event) {
    try {
      reader.onEvent(event);
    } catch (final RuntimeException e) {
      log.debug("Could not answer a subscribe to run {}: {}", reader.requestId, e.toString());
    }
  }

  /** One browser's subscription, as the journal sees it. */
  @RequiredArgsConstructor
  private final class SubscriptionReader implements RunJournal.Reader {

    private final String sessionId;
    private final String subscriptionId;
    private final String destination;
    private final String requestId;

    @Override
    public void onEvent(final RunEvent event) {
      final var headers = StompHeaderAccessor.create(StompCommand.MESSAGE);
      headers.setSessionId(sessionId);
      headers.setSubscriptionId(subscriptionId);
      headers.setDestination(destination);
      headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
      // Left mutable for the ordering decorator, which attaches to each frame the task that
      // releases the session's next one.
      headers.setLeaveMutable(true);
      try {
        final var frame =
            MessageBuilder.createMessage(om.writeValueAsBytes(event), headers.getMessageHeaders());
        if (!outbound(sessionId).send(frame)) {
          // A full or closed channel, which for this reader means the same thing as an exception:
          // it cannot be written to. Rethrown so the journal drops it — see RunJournal.append.
          throw new IllegalStateException("the client channel would not take the frame");
        }
      } catch (final RuntimeException e) {
        // The ordinary end of a subscription, not a failure worth a stack trace: the reader has
        // gone. Rethrown for the same reason.
        log.debug("Reader of run {} has gone: {}", requestId, e.toString());
        throw e instanceof IllegalStateException ? e : new IllegalStateException(e);
      }
    }

    @Override
    public void onClosed() {
      // Nothing to close. The connection is the page's and outlives any one run on it, so an
      // evicted journal only means this subscription has no more to say — the page finds that out
      // from the FINISHED event it was sent long before eviction was a possibility.
    }
  }
}
