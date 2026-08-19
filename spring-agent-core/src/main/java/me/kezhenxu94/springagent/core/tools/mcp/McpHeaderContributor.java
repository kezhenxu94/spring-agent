package me.kezhenxu94.springagent.core.tools.mcp;

import java.util.Map;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Contributes HTTP headers to a call about to be made against a user-registered MCP server.
 *
 * <p>This exists for the headers {@link McpServerConfig#headers()} cannot express: ones whose value
 * depends on who is calling. A config's own headers are fixed when the server is registered and the
 * same for every run; a contributor is asked again for every single call, so it can answer with the
 * caller's identity, a token that has since been refreshed, or nothing at all.
 *
 * <p>Every bean of this type is asked about every call to every server, so a contributor that only
 * has something to say about some of them must say so itself — return an empty map — the same way
 * every {@link me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor} in the
 * application-wide chain gates on a context key it owns.
 *
 * <p>A header returned here overrides a static one of the same name.
 */
public interface McpHeaderContributor {

  /**
   * @param server the server being called, as registered.
   * @param toolContext the run's tool context, the same one a {@code @Tool} method is handed. Read
   *     it through the typed keys in {@link ToolContexts} rather than by string. Note that this is
   *     the context as it stood when the run was assembled: values an integration resolves from its
   *     own thread (an inbound request's credentials, say) have to have been put in it there,
   *     because neither assembly nor the call itself runs on that thread.
   * @return the headers to add, or an empty map. Never null.
   */
  Map<String, String> headers(McpServerConfig server, ToolContext toolContext);
}
