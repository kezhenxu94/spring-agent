package me.kezhenxu94.springagent.integration.feishu.tools;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardUpdater;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * What the card says while a tool call is out, and what it stops saying when the call comes back.
 *
 * <p>Both halves, because the line is written under the answer and would otherwise stand until the
 * model writes its next word. A model that thinks before it writes can leave a long gap there, and
 * a line naming a call that returned in a moment reads as a call that has hung.
 */
@Slf4j
@Component
public class CardUpdateToolInterceptor implements ToolCallInterceptor {

  @Override
  public String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
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
      String toolName, String toolInput, String toolResult, ToolContext toolContext) {
    final var updater = updaterFor(toolName, toolContext);
    if (updater != null) {
      try {
        updater.clearToolStatus();
      } catch (Exception e) {
        log.warn("Failed to clear tool status for '{}': {}", toolName, e.getMessage());
      }
    }
    return toolResult;
  }

  /** The card this call is being made on behalf of, or null where there is no card. */
  private FeishuCardUpdater updaterFor(final String toolName, final ToolContext toolContext) {
    if (toolContext == null) {
      log.debug("Tool '{}' called without context, skipping card update", toolName);
      return null;
    }
    final var updater = ToolContexts.get(toolContext, FeishuCardUpdater.TOOL_CONTEXT_KEY);
    if (updater == null) {
      // Ordinary rather than remarkable: a scheduled task fires with nobody watching and has no
      // card at all, and neither has a run that did not come from Feishu.
      log.debug("No card updater found in context for tool '{}'", toolName);
    }
    return updater;
  }
}
