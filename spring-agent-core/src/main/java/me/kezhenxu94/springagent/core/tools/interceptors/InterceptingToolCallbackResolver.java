package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
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
      // Reached only when the tool was not among the callbacks the iteration offered, so the model
      // called a name the run no longer carries. Per-request tools (TodoWrite, AskUserQuestion, the
      // filesystem and MCP tools) are not beans and can never be resolved by name, so for them this
      // is where the call is lost.
      log.warn(
          "Tool '{}' was called but is not in this iteration's tool callbacks, and {} cannot"
              + " resolve it by name; the call will be answered with an unavailable stub",
          toolName,
          delegate.getClass().getSimpleName());
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
        log.warn(
            "Dropped call to unavailable tool '{}', arguments={}", toolName, abbreviate(toolInput));
        return recoveryMessage(toolName);
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        // The session id is the tool-search advisor's index key and the chat-memory conversation
        // id, so it is what ties this drop to the run it happened in.
        final var context =
            toolContext == null ? Map.<String, Object>of() : toolContext.getContext();
        log.warn(
            "Dropped call to unavailable tool '{}', sessionId={}, arguments={}",
            toolName,
            context.get(ChatMemory.CONVERSATION_ID),
            abbreviate(toolInput));
        return recoveryMessage(toolName);
      }
    };
  }

  /**
   * What the model reads instead of the tool's result. A tool reaches this path because the
   * tool-search advisor did not offer it to the current iteration, and a search naming it is what
   * puts it back, so the way out is worth spelling out: told only that the tool is unavailable, the
   * model abandons the step it was in the middle of.
   */
  private static String recoveryMessage(final String toolName) {
    return "Tool '"
        + toolName
        + "' was not offered to this call, so it did not run. Call toolSearchTool with a query"
        + " naming '"
        + toolName
        + "' to make it available again, then call '"
        + toolName
        + "' once more.";
  }

  /** Enough of the arguments to recognise the call, without a whole tool payload in the log. */
  private static String abbreviate(final String toolInput) {
    if (toolInput == null) {
      return "null";
    }
    return toolInput.length() <= 500 ? toolInput : toolInput.substring(0, 500) + "...";
  }
}
