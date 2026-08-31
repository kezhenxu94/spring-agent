package me.kezhenxu94.springagent.integration.slack.tools;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.integration.slack.handler.SlackMessageUpdater;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * What the reply says while a tool call is out, and what it says once the call comes back.
 *
 * <p>Both halves, because what came back is half of what a call was: the trail keeps every call the
 * turn made along with what each one returned. Without the second half a line announcing a call
 * would stand until the model writes its next word — and on a model that thinks before it writes,
 * that leaves a call which returned in a moment reading as one that has hung.
 */
@Slf4j
@Component
public class SlackMessageUpdateToolInterceptor implements ToolCallInterceptor {

  @Override
  public String beforeCall(
      final String toolName, final String toolInput, final ToolContext toolContext) {
    final var updater = updaterFor(toolName, toolContext);
    if (updater != null) {
      try {
        updater.setToolStatus(toolName, toolInput, toolContext);
      } catch (Exception e) {
        log.warn("Failed to show tool status for '{}': {}", toolName, e.getMessage());
      }
    }
    return toolInput;
  }

  @Override
  public String afterCall(
      final String toolName,
      final String toolInput,
      final String toolResult,
      final ToolContext toolContext) {
    final var updater = updaterFor(toolName, toolContext);
    if (updater != null) {
      try {
        updater.clearToolStatus(toolName, toolInput, toolResult);
      } catch (Exception e) {
        log.warn("Failed to clear tool status for '{}': {}", toolName, e.getMessage());
      }
    }
    return toolResult;
  }

  /** The reply this call is being made on behalf of, or null where there is no reply. */
  private SlackMessageUpdater updaterFor(final String toolName, final ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    final var updater = ToolContexts.get(toolContext, SlackMessageUpdater.TOOL_CONTEXT_KEY);
    if (updater == null) {
      // Ordinary rather than remarkable: a scheduled task fires with nobody watching and has no
      // reply at all, and neither has a run that did not come from Slack.
      log.debug("No updater in context for tool '{}'", toolName);
    }
    return updater;
  }
}
