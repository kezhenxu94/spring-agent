package me.kezhenxu94.springagent.core.agent;

import com.google.common.base.Strings;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * The one way to run the agent. Integrations describe a run as an {@link AgentRequest} and hand it
 * over; everything from there — tool composition, prompt and tool-context assembly, subscription,
 * MCP client lifecycle and listener fan-out — happens here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAgent {

  /**
   * The system prompt is rendered against a fixed variable set (see {@code ai.system-prompt} in the
   * application config), so core supplies the ones an integration may have nothing to say about.
   */
  private static final Map<String, Object> OPTIONAL_PROMPT_VARIABLES =
      Map.of("threadId", "", "parentId", "", "mentions", "none");

  final ChatClient chatClient;

  /**
   * Spring AI's own, deliberately: a {@link ChatMemory} bean declared by the application is what
   * its repository auto-configurations back off from, leaving the in-memory repository in place of
   * the real one.
   */
  final ChatMemory chatMemory;

  final SpringAgentProperties appConfiguration;
  final AgentToolsProvider agentToolsProvider;

  /** What the agent writes into a conversation itself, as opposed to what the model writes. */
  final CoreMessages messages;

  /**
   * The listeners that take part in every run. Resolved lazily rather than injected as a list, so
   * that a listener bean is free to depend on this one.
   */
  final ObjectProvider<AgentResponseListener> declaredListeners;

  private final ConcurrentMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger(0);
  private volatile boolean accepting = true;

  public boolean isAccepting() {
    return accepting;
  }

  public boolean cancel(final String requestId) {
    final var flag = cancelFlags.get(requestId);
    if (flag == null) return false;
    flag.set(true);
    return true;
  }

  @EventListener(ContextClosedEvent.class)
  public void onShutdown() throws InterruptedException {
    accepting = false;
    log.info("Shutdown: waiting for {} in-flight agent stream(s) to complete", inFlight.get());
    final long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
    int elapsed = 0;
    while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(2000);
      elapsed += 2;
      if (elapsed % 30 == 0) {
        log.info(
            "Shutdown: still waiting for {} in-flight agent stream(s) ({} seconds elapsed)",
            inFlight.get(),
            elapsed);
      }
    }
    if (inFlight.get() > 0) {
      log.warn("Shutdown timeout reached with {} agent stream(s) still in flight", inFlight.get());
    } else {
      log.info("Shutdown: all in-flight agent streams completed");
    }
  }

  /**
   * Starts {@code request} and returns immediately. The run reports back through its {@link
   * AgentResponseListener}s, never to the caller.
   */
  public void fire(final AgentRequest request) {
    final var requestId = request.requestId();
    if (!accepting) {
      log.warn("Shutting down, dropping agent request {}", requestId);
      return;
    }

    final var listeners = new ArrayList<>(request.listeners());
    declaredListeners.forEach(listeners::add);

    // Every listener gets its say on the run before any of it is assembled, so that what a listener
    // attaches here is indistinguishable from what the request came with.
    final var registry = new AgentRunRegistry(request);
    notify(listeners, listener -> listener.onStart(registry));
    listeners.addAll(registry.responseListeners());

    if (registry.abortReason() != null) {
      log.warn("Agent request {} aborted before assembly: {}", requestId, registry.abortReason());
      final var aborted = new IllegalStateException(registry.abortReason());
      notify(listeners, l -> l.onError(aborted));
      notify(listeners, l -> l.onFinished(AgentOutcome.FAILED));
      return;
    }

    final var todoEventHandlers = new ArrayList<>(request.todoEventHandlers());
    todoEventHandlers.addAll(registry.todoEventHandlers());

    // A question has one answer, so these cannot fan out the way the handlers above do: the first
    // registered gets to ask, and none at all means the agent is not offered the tool.
    final var questionHandlers = registry.questionHandlers();
    if (questionHandlers.size() > 1) {
      log.warn(
          "Agent request {} has {} question handlers registered; using the first",
          requestId,
          questionHandlers.size());
    }
    final var questionHandler =
        questionHandlers.isEmpty() ? null : recording(request, questionHandlers.getFirst());

    final var cancelFlag = new AtomicBoolean(false);
    if (requestId != null) {
      cancelFlags.put(requestId, cancelFlag);
    }

    // Held so doFinally can release the MCP clients however the run ends, including when assembling
    // it is what failed.
    final var mcpTools = new AtomicReference<McpTools>();
    final var contentBuffer = new StringBuilder();
    final var modelNotified = new AtomicBoolean(false);

    Flux.defer(
            () -> {
              try {
                final var composition =
                    agentToolsProvider.compose(
                        request.userId(),
                        request.chatId(),
                        request.chatType(),
                        request.scenario(),
                        fanOut(todoEventHandlers),
                        questionHandler);
                mcpTools.set(composition.agentTools().mcpTools());
                return rawStream(request, composition, toolContextFor(request, registry));
              } catch (Exception e) {
                return Flux.<ChatResponse>error(e);
              }
            })
        .doOnSubscribe(
            $ -> {
              final var current = inFlight.incrementAndGet();
              log.info("Stream subscribed: requestId={}, inFlight={}", requestId, current);
              notify(listeners, AgentResponseListener::onSubscribe);
            })
        .takeWhile(
            $ -> {
              if (cancelFlag.get()) return false;
              if (listeners.stream().allMatch(AgentResponseListener::shouldContinue)) return true;
              // A listener that stops consuming ends the run the same way an explicit cancel does,
              // so the outcome below reports it as one.
              cancelFlag.set(true);
              return false;
            })
        .doOnNext(
            chatResponse -> {
              final var metadata = chatResponse.getMetadata();
              if (metadata != null) {
                final var model = metadata.getModel();
                if (!Strings.isNullOrEmpty(model) && modelNotified.compareAndSet(false, true)) {
                  notify(listeners, l -> l.onModel(model));
                }
                final var usage = metadata.getUsage();
                if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                  notify(listeners, l -> l.onUsage(model, usage));
                }
              }
              final var result = chatResponse.getResult();
              if (result == null) return;
              final var output = result.getOutput();
              if (output == null) return;
              final var content = output.getText();
              if (Strings.isNullOrEmpty(content)) return;
              contentBuffer.append(content);
              final var contentSoFar = contentBuffer.toString();
              notify(listeners, l -> l.onContent(contentSoFar));
            })
        .doOnError(
            error -> {
              log.error("Agent request {} failed", requestId, error);
              notify(listeners, l -> l.onError(error));
            })
        .doFinally(
            signal -> {
              if (requestId != null) {
                cancelFlags.remove(requestId);
              }
              try {
                // A cancelled run may still surface the aborted read as an error, so the flag
                // decides before the signal does.
                final var outcome =
                    cancelFlag.get()
                        ? AgentOutcome.CANCELLED
                        : switch (signal) {
                          case ON_ERROR -> AgentOutcome.FAILED;
                          case CANCEL -> AgentOutcome.CANCELLED;
                          default -> AgentOutcome.COMPLETED;
                        };
                log.info(
                    "Agent request {} finished: signal={}, outcome={}", requestId, signal, outcome);
                notify(listeners, l -> l.onFinished(outcome));
              } catch (Throwable t) {
                // Not even an Error may cost the run its cleanup: a leaked MCP client holds its
                // connection open, and a missed decrement leaves shutdown waiting out its full
                // timeout for a stream that has already ended.
                log.error("Failed to report the end of agent request {}", requestId, t);
              } finally {
                try {
                  final var tools = mcpTools.get();
                  if (tools != null) {
                    tools.close();
                  }
                } finally {
                  inFlight.decrementAndGet();
                }
              }
            })
        .subscribe();
  }

  /**
   * The handler, plus a note in the conversation saying the questions were put to the user.
   *
   * <p>A plain assistant message because the tool call is not kept: {@code
   * JdbcChatMemoryRepository} drops tool responses and assistant messages carrying tool calls, so a
   * later run would replay the request with no sign anything had been asked — and ask again.
   */
  private QuestionHandler recording(final AgentRequest request, final QuestionHandler delegate) {
    final var conversationId = request.conversationId();
    if (!request.scenario().conversationMemory() || Strings.isNullOrEmpty(conversationId)) {
      return delegate;
    }
    return questions -> {
      final var answers = delegate.handle(questions);
      // Only what actually reached the user gets a note. A handler that declined to ask answers
      // the call all the same, and noting that as an ask would say something untrue — twice over
      // when a model asks again and the handler turns the second one away.
      if (delegate instanceof QuestionPresentation presentation
          && !presentation.presented(answers)) {
        return answers;
      }
      try {
        chatMemory.add(conversationId, List.of(new AssistantMessage(asked(questions))));
      } catch (Exception e) {
        // A missing note costs a repeated ask later, which is not worth failing the run over.
        log.warn("Failed to record the questions put to conversation {}", conversationId, e);
      }
      return answers;
    };
  }

  /**
   * The note itself, addressed to the model that reads it back on a later run — in the workspace's
   * language, so that a conversation held in one language does not carry a turn in another.
   */
  private String asked(final List<Question> questions) {
    final var note = new StringBuilder(messages.get("questions-asked-heading"));
    for (final var question : questions) {
      note.append("\n")
          .append(messages.get("questions-asked-item", question.header(), question.question()));
    }
    return note.append("\n").append(messages.get("questions-asked-footer")).toString();
  }

  /** All handlers as one, fanning each update out to every handler. */
  private static TodoEventHandler fanOut(final List<TodoEventHandler> handlers) {
    return todos -> handlers.forEach(handler -> handler.handle(todos));
  }

  private static void notify(
      final List<AgentResponseListener> listeners, final Consumer<AgentResponseListener> action) {
    for (final var listener : listeners) {
      try {
        action.accept(listener);
      } catch (Exception e) {
        if (e instanceof InterruptedIOException) {
          Thread.currentThread().interrupt();
        } else {
          log.warn(
              "AgentResponseListener {} threw an exception",
              listener.getClass().getSimpleName(),
              e);
        }
      }
    }
  }

  /**
   * The request's own identity plus whatever the listeners contributed. Request values win on
   * conflict, so an integration cannot overwrite the identity the run was started under.
   */
  private static Map<String, Object> toolContextFor(
      final AgentRequest request, final AgentRunRegistry registry) {
    final var toolContext = new LinkedHashMap<String, Object>(registry.toolContext());
    toolContext.putAll(request.toolContext());
    // Emptied rather than left null: ChatClient rejects a tool context with null values outright.
    toolContext.put(ToolContexts.KEY_USER_ID, Strings.nullToEmpty(request.userId()));
    toolContext.put(ToolContexts.KEY_CHAT_ID, Strings.nullToEmpty(request.chatId()));
    toolContext.put(ToolContexts.KEY_CHAT_TYPE, request.chatType());
    toolContext.put(ToolContexts.KEY_ROOT_MESSAGE_ID, Strings.nullToEmpty(request.rootMessageId()));
    toolContext.put(
        ToolContexts.KEY_REPLY_MESSAGE_ID, Strings.nullToEmpty(request.replyMessageId()));
    return toolContext;
  }

  /** Same rule as the tool context: core fills the identity variables, the request the rest. */
  private static Map<String, Object> promptVariablesFor(final AgentRequest request) {
    final var variables = new LinkedHashMap<String, Object>(OPTIONAL_PROMPT_VARIABLES);
    variables.putAll(request.promptVariables());
    variables.put("userId", Strings.nullToEmpty(request.userId()));
    variables.put("chatId", Strings.nullToEmpty(request.chatId()));
    variables.put("chatType", request.chatType());
    return variables;
  }

  private Flux<ChatResponse> rawStream(
      final AgentRequest request,
      final AgentComposition composition,
      final Map<String, Object> toolContext) {
    final var renderedSystemPrompt =
        new SystemPromptTemplate(appConfiguration.ai().systemPrompt())
            .render(promptVariablesFor(request));

    final var advisors = new ArrayList<Advisor>();
    advisors.add(
        AutoMemoryToolsAdvisor.builder()
            .memoriesRootDirectory(composition.memoriesRootDirectory())
            .build());
    if (request.scenario().conversationMemory()) {
      advisors.add(
          MessageChatMemoryAdvisor.builder(chatMemory)
              .order(ToolCallingAdvisor.DEFAULT_ORDER + 100)
              .build());
    }
    advisors.add(SimpleLoggerAdvisor.builder().build());

    return chatClient
        .prompt()
        .system(renderedSystemPrompt)
        .user(request.userMessage())
        .tools(composition.tools())
        .tools((Object[]) composition.toolCallbacks())
        .toolContext(toolContext)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
        .advisors(advisors.toArray(new Advisor[0]))
        .stream()
        .chatResponse();
  }
}
