package me.kezhenxu94.springagent.core.tools.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Map;

/**
 * The one entry this application puts in an {@link McpTransportContext}: the HTTP headers resolved
 * for the call that is about to go out.
 *
 * <p>{@code McpTransportContext} is a string-keyed map shared with the SDK and with anything else
 * that wants to ride along on a call, so the key is declared once here and read back through {@link
 * #from}, rather than spelled out at either end.
 */
final class McpDynamicHeaders {

  private static final String KEY = "springagent.mcp.dynamic-headers";

  private McpDynamicHeaders() {}

  static McpTransportContext carrying(final Map<String, String> headers) {
    return McpTransportContext.create(Map.of(KEY, headers));
  }

  /**
   * Reads the headers back out on the transport side.
   *
   * <p>Every absence is an empty map rather than a failure, because the transport issues requests
   * of its own that no {@code McpSyncClient} call is behind — opening the SSE stream, resuming it,
   * deleting the session on close. Those carry {@link McpTransportContext#EMPTY} (or none at all)
   * and are meant to go out with the static headers only.
   */
  @SuppressWarnings("unchecked")
  static Map<String, String> from(final McpTransportContext context) {
    if (context == null) {
      return Map.of();
    }
    final var headers = context.get(KEY);
    return headers instanceof Map ? (Map<String, String>) headers : Map.of();
  }
}
