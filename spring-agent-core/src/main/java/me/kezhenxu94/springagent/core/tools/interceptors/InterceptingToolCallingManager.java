package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.QueuedMessages;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
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
  private final ToolInputFileRefs fileRefs;

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
                          : new InterceptingToolCallback(cb, interceptors, fileRefs))
              .toList();
      prompt = prompt.mutate().chatOptions(options.mutate().toolCallbacks(wrapped).build()).build();
    }
    return withQueuedMessages(prompt, delegate.executeToolCalls(prompt, chatResponse));
  }

  /**
   * Adds whatever the user said while the run was working to the turn, as the user message it is,
   * after the tool results on their way back to the model.
   *
   * <p>This is the one point in a turn where a message can be added without disturbing it: the tool
   * calls the model made have all been answered, nothing is outstanding, and the list handed back
   * here is exactly what the advisor sends as the next iteration's messages. A message inserted
   * anywhere else would sit between an assistant message and the results answering it, which a
   * provider rejects outright.
   *
   * <p>Nothing is taken from the queue when the result is to be returned direct: the loop ends
   * there, so the message would be added to a history no model reads again. It stays queued, and
   * the run ends with it unread — which is what has {@code SpringAgent} answer it as a run of its
   * own.
   */
  private ToolExecutionResult withQueuedMessages(
      final Prompt prompt, final ToolExecutionResult result) {
    if (result == null || result.returnDirect()) {
      return result;
    }
    final var queued = queuedMessagesOf(prompt);
    if (queued == null) {
      return result;
    }
    final var arrived = queued.read();
    if (arrived.isEmpty()) {
      return result;
    }
    log.info("Adding {} message(s) that arrived mid-run to the turn", arrived.size());
    final var history = new ArrayList<>(result.conversationHistory());
    // Already framed by QueuedMessages, which is the only party that knows whether a message came
    // from the person whose run this is or from an administrator speaking into it.
    arrived.forEach(message -> history.add(new UserMessage(message)));
    return ToolExecutionResult.builder()
        .conversationHistory(history)
        .returnDirect(result.returnDirect())
        .build();
  }

  /** The run's queue, or null for a run assembled by something other than {@code SpringAgent}. */
  private static QueuedMessages queuedMessagesOf(final Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
        || options.getToolContext() == null) {
      return null;
    }
    return ToolContexts.get(
        new ToolContext(options.getToolContext()), ToolContexts.QUEUED_MESSAGES);
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
