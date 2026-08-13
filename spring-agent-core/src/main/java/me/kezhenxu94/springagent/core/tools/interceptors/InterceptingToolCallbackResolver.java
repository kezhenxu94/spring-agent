package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

@Slf4j
public class InterceptingToolCallbackResolver implements ToolCallbackResolver {

  private final ToolCallbackResolver delegate;
  private final List<ToolCallInterceptor> interceptors;

  public InterceptingToolCallbackResolver(
      ToolCallbackResolver delegate, List<ToolCallInterceptor> interceptors) {
    this.delegate = delegate;
    this.interceptors = interceptors;
  }

  @Override
  public ToolCallback resolve(String toolName) {
    final var callback = delegate.resolve(toolName);
    if (callback == null) {
      log.info(
          "Tool '{}' is not available from delegate resolver, using unavailable callback",
          toolName);
      return unavailableToolCallback(toolName);
    }
    return new InterceptingToolCallback(callback, interceptors);
  }

  private static ToolCallback unavailableToolCallback(String toolName) {
    final var definition =
        ToolDefinition.builder().name(toolName).description("").inputSchema("{}").build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        log.info("Attempted to call unavailable tool '{}'", toolName);
        return "Tool '" + toolName + "' is not available.";
      }

      @Override
      public String call(String toolInput, ToolContext toolContext) {
        log.info("Attempted to call unavailable tool '{}' with context", toolName);
        return "Tool '" + toolName + "' is not available.";
      }
    };
  }
}
