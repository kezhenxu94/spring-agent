package me.kezhenxu94.springagent.events.source;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One HTTP delivery as it arrived, before anything has interpreted it.
 *
 * <p>The body is bytes and stays bytes. Every source here authenticates by signing the body, and a
 * signature is over what was actually sent: parsing to a string or to a tree and back re-encodes it
 * — key order, escaping, whitespace — and the signature then fails to match for reasons that look
 * like a wrong secret. So the raw array is what is carried, and reading it as text is the caller's
 * explicit step.
 *
 * <p>Deliberately not {@code HttpHeaders} or {@code HttpServletRequest}, which is what keeps a
 * source a plain object testable with a map and an array rather than with a servlet fixture.
 *
 * @param headers the request headers, matched without regard to case, as HTTP requires and as
 *     proxies along the way take advantage of
 * @param body the request body verbatim
 */
public record WebhookDelivery(Map<String, String> headers, byte[] body) {

  public WebhookDelivery {
    final var insensitive = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) {
      headers.forEach(
          (name, value) -> {
            if (name != null) {
              insensitive.put(name, value);
            }
          });
    }
    // Not Map.copyOf: that returns a map with ordinary key equality, which would throw the
    // case-insensitivity away again. Wrapped instead, so the comparator survives and the caller
    // still cannot write to it.
    headers = Collections.unmodifiableMap(insensitive);
    body = body == null ? new byte[0] : body.clone();
  }

  /** The named header, or null where the request did not carry one. */
  public String header(final String name) {
    return headers.get(name);
  }

  /** The body as UTF-8 text, for parsing. Never for verifying — see the note on {@link #body}. */
  public String bodyAsText() {
    return new String(body, StandardCharsets.UTF_8);
  }
}
