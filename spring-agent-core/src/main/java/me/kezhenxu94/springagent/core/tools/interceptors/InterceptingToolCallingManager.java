package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

@RequiredArgsConstructor
public class InterceptingToolCallingManager implements ToolCallingManager {
  private final ToolCallingManager delegate;
  private final List<ToolCallInterceptor> interceptors;

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
    return delegate.resolveToolDefinitions(chatOptions);
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      final var wrapped =
          options.getToolCallbacks().stream()
              .<ToolCallback>map(
                  cb ->
                      cb instanceof InterceptingToolCallback
                          ? cb
                          : new InterceptingToolCallback(cb, interceptors))
              .toList();
      prompt = prompt.mutate().chatOptions(options.mutate().toolCallbacks(wrapped).build()).build();
    }
    return delegate.executeToolCalls(prompt, chatResponse);
  }
}
