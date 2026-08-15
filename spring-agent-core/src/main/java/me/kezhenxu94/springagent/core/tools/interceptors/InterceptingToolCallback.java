package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class InterceptingToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final List<ToolCallInterceptor> interceptors;

  public InterceptingToolCallback(ToolCallback delegate, List<ToolCallInterceptor> interceptors) {
    this.delegate = delegate;
    this.interceptors = interceptors;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  /**
   * Delegated like the definition, and for a sharper reason: {@code ToolCallback} defaults this to
   * metadata with {@code returnDirect} off, and every callback a run carries passes through here.
   * Not forwarding it silently turned a tool that ends the turn into one that does not.
   */
  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    var input = applyBefore(toolInput, null);
    return applyAfter(input, delegate.call(input), null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    var input = applyBefore(toolInput, toolContext);
    return applyAfter(input, delegate.call(input, toolContext), toolContext);
  }

  private String applyBefore(String input, ToolContext ctx) {
    final var name = getToolDefinition().name();
    for (final var interceptor : interceptors) {
      input = interceptor.beforeCall(name, input, ctx);
    }
    return input;
  }

  private String applyAfter(String input, String result, ToolContext ctx) {
    final var name = getToolDefinition().name();
    for (final var interceptor : interceptors) {
      result = interceptor.afterCall(name, input, result, ctx);
    }
    return result;
  }
}
