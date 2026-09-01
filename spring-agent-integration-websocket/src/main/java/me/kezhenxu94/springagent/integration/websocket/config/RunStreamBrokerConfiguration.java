package me.kezhenxu94.springagent.integration.websocket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The one websocket this surface opens, and the destinations on it.
 *
 * <p>One endpoint and one kind of subscription: a browser watching a run. Everything else the page
 * does is an ordinary request, because everything else is a question with an answer — what
 * conversations are mine, what did this one say, send this message. A run is the one thing that
 * happens over time, and the one thing the server has to be able to speak about unprompted.
 *
 * <p>Plain WebSocket rather than SockJS. The fallbacks SockJS exists for are for browsers and
 * proxies that cannot carry a websocket at all, and this page already requires a browser modern
 * enough that its absence would be the smaller problem.
 *
 * <p><b>{@code /app} is load-bearing, and a consuming application that configures a broker of its
 * own must agree with it.</b> {@code RunStreamSubscriptions} maps {@code /runs/{requestId}} under
 * this prefix, and every {@code WebSocketMessageBrokerConfigurer} in a context is applied to the
 * same registry — so an application that sets a different application destination prefix does not
 * get two schemes, it silently moves this module's subscription out from under the page that
 * subscribes to it.
 */
@Configuration
@EnableWebSocketMessageBroker
public class RunStreamBrokerConfiguration implements WebSocketMessageBrokerConfigurer {

  /** Where the page opens its connection. Authorised as an ordinary request; see the module's. */
  public static final String ENDPOINT = "/ws/runs";

  /** What a destination the page subscribes to is prefixed with. */
  public static final String APPLICATION_PREFIX = "/app";

  @Override
  public void registerStompEndpoints(final StompEndpointRegistry registry) {
    // No setAllowedOrigins: the default is same-origin, which is what a page served by this
    // application from this application wants. Widening it would let any site open a connection
    // carrying the reader's cookie.
    registry.addEndpoint(ENDPOINT);
  }

  @Override
  public void configureMessageBroker(final MessageBrokerRegistry registry) {
    registry.setApplicationDestinationPrefixes(APPLICATION_PREFIX);
    // Enabled for the CONNECT handshake rather than for any destination this module uses: a STOMP
    // client waits for a CONNECTED frame, and the broker is what sends it. Nothing here publishes
    // to /topic — a run is streamed to the one subscriber that asked for it, from
    // RunStreamSubscriptions, because replay from that subscriber's own cursor is the whole point
    // and a topic would hand one browser's backlog to every other browser watching.
    registry.enableSimpleBroker("/topic");
  }
}
