package me.kezhenxu94.springagent.events.situation;

import java.util.regex.Pattern;

/**
 * Whether a failed triage run failed for a reason that may pass on its own, and so is worth asking
 * again on a timer rather than leaving for the next observation.
 *
 * <p>Transient: the model endpoint answering 5xx, 429 or 408, a timeout, a connection refused,
 * reset or closed, an MCP server going away. Not: any other 4xx — the request itself was wrong, and
 * asking again sends the same request — and anything this does not recognise, since retrying what
 * nobody understood is how a permanent failure is retried for ever.
 *
 * <p>Read from the whole cause chain, and from names and messages rather than types: the failure
 * arrives wrapped several deep, from whichever provider's SDK, and depending on none of them is
 * what keeps this module free of every one. The first status code found decides; a link naming no
 * status is read by its type.
 */
final class TransientFailures {

  /** A status code where an error message begins with one, as every provider SDK here writes it. */
  private static final Pattern LEADING_STATUS = Pattern.compile("^\\s*(\\d{3})\\b");

  private TransientFailures() {}

  static boolean isTransient(final Throwable error) {
    for (var link = error; link != null; link = link.getCause()) {
      final var status = status(link);
      if (status > 0) {
        return status >= 500 || status == 429 || status == 408;
      }
      if (transientType(link)) {
        return true;
      }
      if (link.getCause() == link) {
        break;
      }
    }
    return false;
  }

  /** The HTTP status {@code error} reports, or 0 where it names none. */
  private static int status(final Throwable error) {
    final var name = error.getClass().getSimpleName();
    if (name.contains("RateLimit")) {
      return 429;
    }
    if (name.contains("InternalServer")
        || name.contains("ServiceUnavailable")
        || name.contains("BadGateway")
        || name.contains("GatewayTimeout")) {
      return 503;
    }
    if (name.contains("BadRequest")
        || name.contains("Unauthorized")
        || name.contains("PermissionDenied")
        || name.contains("Forbidden")
        || name.contains("NotFound")
        || name.contains("UnprocessableEntity")) {
      return 400;
    }
    final var message = error.getMessage();
    if (message == null) {
      return 0;
    }
    final var matcher = LEADING_STATUS.matcher(message);
    if (matcher.find()) {
      final var code = Integer.parseInt(matcher.group(1));
      return code >= 400 && code < 600 ? code : 0;
    }
    return 0;
  }

  /** A failure of the connection or of a server's session rather than of what was asked. */
  private static boolean transientType(final Throwable error) {
    final var type = error.getClass();
    final var name = type.getSimpleName();
    if (name.contains("Timeout")
        || name.contains("ConnectException")
        || name.contains("SocketException")
        || name.contains("ClosedChannel")
        || name.contains("EOFException")
        || name.contains("PrematureClose")) {
      return true;
    }
    // An MCP server that went away mid-run — redeployed, its session terminated.
    if (type.getName().startsWith("io.modelcontextprotocol.")) {
      return true;
    }
    final var message = error.getMessage();
    // The SDK reports a terminated session as a plain RuntimeException, so only its words say so.
    if (message != null && message.contains("MCP session") && message.contains("terminated")) {
      return true;
    }
    return message != null
        && error instanceof java.io.IOException
        && (message.contains("reset")
            || message.contains("closed")
            || message.contains("broken pipe")
            || message.contains("EOF"));
  }
}
