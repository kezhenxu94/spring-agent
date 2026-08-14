package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

@Slf4j
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
      logMissingTools(options, chatResponse);
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

  /**
   * The tool-search advisor rebuilds the callbacks before every iteration, keeping only {@code
   * toolSearchTool} and whatever a surviving search result named, so a tool the run does carry can
   * still be missing from the call the model is making. Whatever is missing here falls through to
   * the resolver, and a per-request tool is lost there — so the names on both sides are logged
   * while they are still known.
   */
  private static void logMissingTools(
      final ToolCallingChatOptions options, final ChatResponse chatResponse) {
    if (chatResponse == null || chatResponse.getResults() == null) {
      return;
    }
    final var requested =
        chatResponse.getResults().stream()
            .filter(result -> result.getOutput() != null)
            .flatMap(result -> result.getOutput().getToolCalls().stream())
            .map(AssistantMessage.ToolCall::name)
            .toList();
    if (requested.isEmpty()) {
      return;
    }
    final var callbacks =
        options.getToolCallbacks() == null ? List.<ToolCallback>of() : options.getToolCallbacks();
    final var offered =
        callbacks.stream()
            .map(callback -> callback.getToolDefinition().name())
            .collect(Collectors.toSet());
    final var missing = requested.stream().filter(name -> !offered.contains(name)).toList();
    if (!missing.isEmpty()) {
      log.warn(
          "Tools {} were called but not offered to that iteration; offered={}", missing, offered);
    }
  }
}
