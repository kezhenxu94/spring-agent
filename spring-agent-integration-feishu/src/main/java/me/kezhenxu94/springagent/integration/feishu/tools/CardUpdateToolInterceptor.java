package me.kezhenxu94.springagent.integration.feishu.tools;

import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardUpdater;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CardUpdateToolInterceptor implements ToolCallInterceptor {

  @Override
  public String beforeCall(String toolName, String toolInput, ToolContext toolContext) {
    if (toolContext == null) {
      log.debug("Tool '{}' called without context, skipping card update", toolName);
      return toolInput;
    }
    final var updater = ToolContexts.get(toolContext, FeishuCardUpdater.TOOL_CONTEXT_KEY);
    if (updater == null) {
      // Ordinary rather than remarkable: a scheduled task fires with nobody watching and has no
      // card at all, and neither has a run that did not come from Feishu.
      log.debug("No card updater found in context for tool '{}'", toolName);
      return toolInput;
    }
    try {
      updater.setToolStatus(toolName, toolInput, toolContext);
    } catch (Exception e) {
      log.warn("Failed to show tool status for '{}': {}", toolName, e.getMessage());
    }
    return toolInput;
  }
}
