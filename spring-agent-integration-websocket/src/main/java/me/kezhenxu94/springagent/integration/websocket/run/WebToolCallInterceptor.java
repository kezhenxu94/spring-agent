package me.kezhenxu94.springagent.integration.websocket.run;

import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Reports the tool the agent is running, and what it returned.
 *
 * <p>Tool calls do not reach a surface through {@code AgentResponseListener} — the interceptor
 * chain is the only place they are visible. A bean, because that chain is application-wide, and a
 * no-op for a run that put no renderer in its tool context, which is every run this module did not
 * attach to. The same shape as {@code CliToolCallInterceptor}.
 */
@Component
public class WebToolCallInterceptor implements ToolCallInterceptor {

  @Override
  public String beforeCall(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    final var renderer = ToolContexts.get(toolContext, WebRunRenderer.TOOL_CONTEXT_KEY);
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
    final var renderer = ToolContexts.get(toolContext, WebRunRenderer.TOOL_CONTEXT_KEY);
    if (renderer != null) {
      renderer.onToolResult(toolName, toolInput, toolResult);
    }
    return toolResult;
  }
}
