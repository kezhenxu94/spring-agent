package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.exception.HandlerNotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * The event dispatcher, with an event nobody here handles treated as nothing rather than as a
 * failure.
 *
 * <p>Feishu pushes every event the application is subscribed to, and a subscription is made in the
 * developer console rather than in this code — so the set of events arriving is always wider than
 * the set of handlers registered, and grows on its own as Feishu adds events to a subscription
 * scope. The SDK's dispatcher answers an unregistered type by throwing {@link
 * HandlerNotFoundException}, which the long connection's client logs at ERROR with a full stack
 * trace and answers with a 500. So the ordinary state of a healthy deployment was a log full of
 * failures for events it was never going to do anything with.
 *
 * <p>A subclass rather than {@code EventDispatcher.Builder.onCustomizedEvent}, which is the only
 * other thing the SDK offers: that one registers a handler against one exact event type string, so
 * it can silence the events somebody thought to name and nothing else. Every event Feishu adds
 * later would be a fresh error until a human noticed the log and added a line. Overriding the one
 * method the client calls silences the whole class of them, once.
 *
 * <p>Returning {@code null} rather than rethrowing is what makes the reply a 200: an event this
 * application chose not to handle was delivered successfully, and answering 500 asks Feishu to send
 * it again — turning one unhandled event into a redelivery loop.
 */
@Slf4j
final class LenientEventDispatcher extends EventDispatcher {

  LenientEventDispatcher(final EventDispatcher.Builder builder) {
    super(builder);
  }

  /**
   * The method the long connection's client calls for every event frame. The webhook path, {@code
   * handle(EventReq)}, already passes over an unknown type on its own; this one propagates, which
   * is why it is the one overridden.
   */
  @Override
  public Object doWithoutValidation(final byte[] payload) throws Throwable {
    try {
      return super.doWithoutValidation(payload);
    } catch (HandlerNotFoundException e) {
      // The exception carries the event type only in its message; there is no getter for it.
      log.debug("Ignoring a Feishu event nothing here handles: {}", e.getMessage());
      return null;
    }
  }
}
