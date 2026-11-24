package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.advisors.TurnContextAdvisor;
import me.kezhenxu94.springagent.core.agent.QueuedMessages;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

  /** What a call the model did not finish writing is answered with, in the workspace's language. */
  private final CoreMessages messages;

  @Override
  public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
    return delegate.resolveToolDefinitions(chatOptions);
  }

  @Override
  public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      final var wrapped =
          new ArrayList<ToolCallback>(
              options.getToolCallbacks().stream()
                  .<ToolCallback>map(
                      cb ->
                          // The dispatcher wraps the tool it calls; wrapped itself, every
                          // interceptor would see that call twice.
                          cb instanceof InterceptingToolCallback
                                  || cb instanceof DispatchingToolCallback
                              ? cb
                              : new InterceptingToolCallback(cb, interceptors, fileRefs, messages))
                  .toList());
      wrapped.addAll(directCalls(options, chatResponse));
      prompt = prompt.mutate().chatOptions(options.mutate().toolCallbacks(wrapped).build()).build();
    }
    return withQueuedMessages(prompt, asJson(delegate.executeToolCalls(prompt, chatResponse)));
  }

  /**
   * Every tool response of the turn in the shape Spring AI promises: JSON.
   *
   * <p>The backstop, and the only place that sees <em>all</em> of them. {@code
   * InterceptingToolCallback} covers the answers this project substitutes, but two more are
   * produced inside {@code DefaultToolCallingManager} itself, after any callback has returned or
   * instead of calling one at all: a thrown tool becomes {@code
   * toolExecutionExceptionProcessor.process(ex)}, and a run over the tool-call limit becomes {@code
   * limitBreach.message()}. Both are prose, and an MCP server that fails is the common way to meet
   * the first.
   *
   * <p>Left unencoded, those reach Gemini as something it cannot parse and end the run on {@code
   * Stream processing failed}, naming neither the tool nor the failure it was trying to report —
   * the error about the error is what gets lost. See {@link ToolResultJson}.
   *
   * <p>Rebuilt rather than mutated because a {@code ToolResponseMessage} is immutable, and left
   * alone entirely when nothing needed changing, which is the ordinary turn.
   */
  private static ToolExecutionResult asJson(final ToolExecutionResult result) {
    if (result == null || result.conversationHistory() == null) {
      return result;
    }
    var changed = false;
    final var history = new ArrayList<Message>(result.conversationHistory().size());
    for (final var message : result.conversationHistory()) {
      if (!(message instanceof ToolResponseMessage toolResponses)) {
        history.add(message);
        continue;
      }
      final var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
      for (final var response : toolResponses.getResponses()) {
        final var encoded = ToolResultJson.asJson(response.responseData());
        changed |= !encoded.equals(response.responseData());
        responses.add(
            new ToolResponseMessage.ToolResponse(response.id(), response.name(), encoded));
      }
      history.add(
          ToolResponseMessage.builder()
              .responses(responses)
              .metadata(toolResponses.getMetadata())
              .build());
    }
    if (!changed) {
      return result;
    }
    log.debug("Encoded a tool response that was not JSON, so every provider can read it back");
    return ToolExecutionResult.builder()
        .conversationHistory(history)
        .returnDirect(result.returnDirect())
        .build();
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
    // Defused as the message that opened the turn was: only the runtime writes a system-reminder.
    arrived.forEach(message -> history.add(new UserMessage(TurnContextAdvisor.defuse(message))));
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
   * A callback for each tool the model called by its own name though the iteration did not offer
   * it, where it is one the run's {@code CallTool} reaches — run exactly as {@code CallTool} would
   * run it. The tool search offers a fixed set directly and lists the rest in the system prompt,
   * and a model that read a name there sometimes calls it as it would any tool. Answered with the
   * stub instead, the work was dropped and cost a retry; offering these to the model is not what
   * happens here, so the {@code tools} array a prompt cache depends on is untouched.
   *
   * <p>Whatever is called and is not one of those falls through to the resolver, and a per-request
   * tool is lost there — so those names are logged while they are still known.
   */
  private static List<ToolCallback> directCalls(
      final ToolCallingChatOptions options, final ChatResponse chatResponse) {
    if (chatResponse == null || chatResponse.getResults() == null) {
      return List.of();
    }
    final var callbacks =
        options.getToolCallbacks() == null ? List.<ToolCallback>of() : options.getToolCallbacks();
    final var offered =
        callbacks.stream()
            .map(callback -> callback.getToolDefinition().name())
            .collect(Collectors.toSet());
    final var missing =
        chatResponse.getResults().stream()
            .filter(result -> result.getOutput() != null)
            .flatMap(result -> result.getOutput().getToolCalls().stream())
            .map(AssistantMessage.ToolCall::name)
            .filter(name -> !offered.contains(name))
            .distinct()
            .toList();
    if (missing.isEmpty()) {
      return List.of();
    }
    final var dispatcher =
        callbacks.stream()
            .filter(DispatchingToolCallback.class::isInstance)
            .map(DispatchingToolCallback.class::cast)
            .findFirst()
            .orElse(null);
    final var direct = new ArrayList<ToolCallback>();
    final var unknown = new ArrayList<String>();
    for (final var name : missing) {
      final var call = dispatcher == null ? null : dispatcher.direct(name);
      if (call == null) {
        unknown.add(name);
      } else {
        direct.add(call);
      }
    }
    if (!direct.isEmpty()) {
      log.info(
          "Tools {} were called directly rather than through CallTool; running them as it would",
          direct.stream().map(call -> call.getToolDefinition().name()).toList());
    }
    if (!unknown.isEmpty()) {
      log.warn(
          "Tools {} were called but not offered to that iteration; offered={}", unknown, offered);
    }
    return direct;
  }
}
