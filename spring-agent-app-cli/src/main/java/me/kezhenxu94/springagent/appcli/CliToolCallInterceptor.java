package me.kezhenxu94.springagent.appcli;

import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Shows the tool the agent is running, and a glimpse of what it returned.
 *
 * <p>Mirrors {@code CardUpdateToolInterceptor}: a bean, because the interceptor chain is
 * application-wide, but it does nothing for a run that put no renderer in its tool context — which
 * is every run this integration did not start.
 */
@Component
public class CliToolCallInterceptor implements ToolCallInterceptor {

  @Override
  public String beforeCall(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    final var renderer = ToolContexts.get(toolContext, CliRenderer.TOOL_CONTEXT_KEY);
    if (renderer != null) {
      renderer.onToolCall(toolName, toolInput);
    }
    return toolInput;
  }

  @Override
  public String afterCall(
      final String toolName,
      final String toolInput,
      final String toolResult,
      final ToolContext toolContext) {
    final var renderer = ToolContexts.get(toolContext, CliRenderer.TOOL_CONTEXT_KEY);
    if (renderer != null) {
      renderer.onToolResult(toolResult);
    }
    return toolResult;
  }
}
