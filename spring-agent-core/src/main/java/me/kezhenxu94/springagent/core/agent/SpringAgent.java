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
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.tools.QuestionNotAnsweredException;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
   *
   * <p>{@code replyFormat} is the slot a surface's own rules go in — the markdown its client
   * renders, the tags it understands — filled by a {@link PromptVariablesContributor} and empty on
   * a surface that has nothing to say about how a reply looks.
   */
  private static final Map<String, Object> OPTIONAL_PROMPT_VARIABLES =
      Map.of("threadId", "", "parentId", "", "mentions", "none", "replyFormat", "");

  /**
   * Where the tool search looks for the index to use. Has to agree with {@code
   * spring.ai.chat.client.tool-search-advisor.session-id-key-name}, which is what the advisor reads
   * the advisor context by; the two are set apart because the advisor's default is the conversation
   * id and this deliberately is not. See {@link #toolIndexKeyFor}.
   */
  public static final String TOOL_INDEX_KEY = "toolIndexKey";

  final ChatClient chatClient;

  /**
   * Spring AI's own, deliberately: a {@link ChatMemory} bean declared by the application is what
   * its repository auto-configurations back off from, leaving the in-memory repository in place of
   * the real one.
   */
  final ChatMemory chatMemory;

  final SpringAgentProperties appConfiguration;
  final AgentToolsProvider agentToolsProvider;

  /** Read only to see whether a conversation already has questions out; the channels write it. */
  final PendingQuestionRepo pendingQuestions;

  /** What the agent writes into a conversation itself, as opposed to what the model writes. */
  final CoreMessages messages;

  /**
   * The listeners that take part in every run. Resolved lazily rather than injected as a list, so
   * that a listener bean is free to depend on this one.
   */
  final ObjectProvider<AgentResponseListener> declaredListeners;

  /**
   * The surfaces filling a system-prompt slot for every run. Resolved lazily for the same reason as
   * the listeners above.
   */
  final ObjectProvider<PromptVariablesContributor> promptVariablesContributors;

  /** Only present on a backend whose chat memory cannot keep tool calls. */
  final ObjectProvider<AskedQuestionsRecorder> askedQuestionsRecorder;

  /**
   * The tool advisor's own builder, which is the tool-search one where that advisor is configured
   * (see {@code ToolSearchAdvisorAutoConfiguration}, which registers it under this type so it
   * replaces the plain {@code ToolCallingAdvisor}). Taken so the advisor can be registered here
   * rather than left to {@code ChatClient} to register — see {@link #rawStream} for why.
   */
  final ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder;

  /**
   * The one tool advisor every run shares, built on first use.
   *
   * <p>Load-bearing that it is one instance and not one per run. The tool-search advisor keeps the
   * fingerprint of the tool set it last indexed per index key, and skips re-indexing while that
   * fingerprint holds; it also holds the eviction strategy deciding when an index is dropped. Both
   * live on the instance, so a fresh advisor per run starts out knowing nothing and re-indexes
   * every tool on every request — which with a few hundred MCP tools is an embedding call per tool
   * before the model is even asked anything.
   *
   * <p>Resolved lazily rather than in the constructor, so that the builder bean is not required to
   * exist before this one and a deployment with no tool advisor configured still starts.
   */
  private final AtomicReference<Advisor> toolCallingAdvisor = new AtomicReference<>();

  private final ConcurrentMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
  private final AtomicInteger inFlight = new AtomicInteger(0);

  @Getter private volatile boolean accepting = true;

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

    // Every channel taking part gets to put the questions to the user, so an answer can come back
    // from whichever the user is looking at. None registered means the agent is not offered the
    // tool at all.
    final var questionHandlers = registry.questionHandlers();
    // Whether an answer can come back inside the call at all. Two things follow: what the ask is
    // validated against, and whether the run ends at it rather than asking the model to stop.
    final var answersArriveLater =
        questionHandlers.stream().noneMatch(SynchronousQuestionHandler.class::isInstance);
    final var questionHandler =
        questionHandlers.isEmpty() ? null : asking(request, questionHandlers, answersArriveLater);

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
                // Assembled before composition, not just for the run: an MCP server is called with
                // the headers its contributors derive from this map, so it has to exist before the
                // clients that will consult it are built.
                final var toolContext = toolContextFor(request, registry);
                final var composition =
                    agentToolsProvider.compose(
                        request,
                        toolContext,
                        fanOut(todoEventHandlers),
                        questionHandler,
                        answersArriveLater);
                mcpTools.set(composition.mcpTools());
                return rawStream(request, composition, toolContext);
              } catch (Exception e) {
                return Flux.<ChatResponse>error(e);
              }
            })
        // Without this, "returns immediately" above is false: subscribing runs everything up to the
        // model's first asynchronous boundary on whatever thread called fire(), and that includes
        // all of assembly — the MCP handshakes, one per server, and the tool-index build. The
        // callers are event dispatchers with delivery deadlines; Feishu concludes a message it is
        // still waiting on was never delivered and sends it again, so a slow assembly turned into
        // duplicate answers. boundedElastic because everything being moved is blocking.
        .subscribeOn(Schedulers.boundedElastic())
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
   * Every registered handler as one ask: each is given the questions, and what they answer with is
   * merged into the one map the tool takes.
   *
   * <p>Most asks come back with no answer at all — the user has an hour to think about it, and the
   * run cannot be held open for that. So this is also where it is decided what the model reads in
   * place of one, thrown as a {@link QuestionNotAnsweredException}.
   *
   * <p>A handler that throws anything else is logged and passed over rather than failing the ask,
   * so a channel that is broken or unreachable does not cost the user the channels that are
   * neither. Returning is therefore what counts as having put the questions somewhere, and if none
   * of them managed it the model is told so.
   *
   * <p>Answers are merged first-one-wins per question, which only arises for a synchronous handler
   * beside an asynchronous one — a combination no application composes today, the command line
   * being alone in its own.
   */
  private QuestionHandler asking(
      final AgentRequest request,
      final List<QuestionHandler> handlers,
      final boolean answersArriveLater) {
    return questions -> {
      // One unanswered ask per conversation, whatever the channel: a model that asks again while a
      // form is still up would otherwise put a second one in front of the user on every channel.
      if (hasOutstandingAsk(request)) {
        log.info(
            "Not asking again in conversation {}: one is already waiting",
            request.conversationId());
        throw new QuestionNotAnsweredException(messages.get("question-already-asked"));
      }

      final var answers = new LinkedHashMap<String, String>();
      QuestionNotAnsweredException settled = null;
      var presented = 0;
      for (final var handler : handlers) {
        try {
          final var handlerAnswers = handler.handle(questions);
          if (handlerAnswers != null) {
            handlerAnswers.forEach(answers::putIfAbsent);
          }
          presented++;
        } catch (QuestionNotAnsweredException e) {
          // Put up and settled without an answer — the user dismissed them, say. Kept rather than
          // rethrown, so the channels after this one still get their turn.
          if (settled == null) {
            settled = e;
          }
          presented++;
        } catch (Exception e) {
          log.warn(
              "Question handler {} could not put questions to the user",
              handler.getClass().getSimpleName(),
              e);
        }
      }
      if (presented == 0) {
        throw new QuestionNotAnsweredException(messages.get("question-cannot-ask"));
      }

      // A real answer from any channel beats a note from another: the user did reply somewhere.
      if (!answers.isEmpty()) {
        return answers;
      }

      // Only with no answer to them: the note says to wait rather than ask again, which is false
      // once an answer is in hand.
      recordAsked(request);

      if (settled != null) {
        throw settled;
      }
      // With the turn ending here the model never reads this, and the user does: the tool's result
      // is what Spring AI returns to the application in place of a reply. The headers are the
      // model's own short labels for what it just put in front of them.
      throw new QuestionNotAnsweredException(
          answersArriveLater ? headersOf(questions) : messages.get("question-asked"));
    };
  }

  /** Only on the backends whose chat memory cannot keep the tool call that would say this. */
  private void recordAsked(final AgentRequest request) {
    final var conversationId = request.conversationId();
    if (!request.scenario().conversationMemory() || Strings.isNullOrEmpty(conversationId)) {
      return;
    }
    askedQuestionsRecorder.ifAvailable(recorder -> recorder.record(conversationId));
  }

  /**
   * What the user reads in place of a reply when the turn ends at the ask: the model's own short
   * label for each question, which is already written for them and sits above the form itself.
   */
  private static String headersOf(final List<Question> questions) {
    return questions.stream().map(Question::header).collect(Collectors.joining(", "));
  }

  /** Whether the conversation already has questions out that nobody has answered. */
  private boolean hasOutstandingAsk(final AgentRequest request) {
    final var conversationId = request.conversationId();
    if (Strings.isNullOrEmpty(conversationId)) {
      return false;
    }
    try {
      return !pendingQuestions
          .findByConversationIdAndStatus(conversationId, PendingQuestion.Status.PENDING)
          .isEmpty();
    } catch (Exception e) {
      // Asking twice is a smaller failure than not asking at all.
      log.warn("Could not check for outstanding questions in conversation {}", conversationId, e);
      return false;
    }
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
  private Map<String, Object> promptVariablesFor(final AgentRequest request) {
    final var variables = new LinkedHashMap<String, Object>(OPTIONAL_PROMPT_VARIABLES);
    // Between the defaults and the request: a contributor speaks for a surface in general, the
    // request for this one run, so the run's own word is the later one.
    promptVariablesContributors.forEach(
        contributor -> {
          try {
            variables.putAll(contributor.variables(request));
          } catch (Exception e) {
            // A surface that cannot say how it renders a reply is worth less than a run that never
            // happens: the slot stays at its default and the answer arrives unstyled.
            log.warn(
                "Prompt variables contributor {} failed; carrying on without it",
                contributor.getClass().getName(),
                e);
          }
        });
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

    // The tools the composition delivers as advisors rather than as callbacks, auto-memory among
    // them. What follows is the run's own wiring, which is nothing to do with tools.
    final var advisors = new ArrayList<Advisor>(composition.advisors());
    if (request.scenario().conversationMemory()) {
      // Ordered after the tool advisor, so it sits inside the tool-calling loop and sees each
      // iteration: the assistant message carrying the tool calls, and the tool responses that
      // answer them. That is what puts a turn's tool calls into chat memory at all.
      advisors.add(
          MessageChatMemoryAdvisor.builder(chatMemory)
              .order(ToolCallingAdvisor.DEFAULT_ORDER + 100)
              .build());
    }
    // The tool advisor is registered here rather than left to ChatClient for two reasons.
    //
    // One, to keep its own conversation history on. ChatClient registers it for us only when we
    // have not, and turns that history off whenever a memory advisor sits downstream — on the
    // assumption that chat memory will carry the loop's messages instead. No repository keeps tool
    // messages (JdbcChatMemoryRepository refuses them outright, logging a warning as it drops
    // them), so on that assumption the loop loses its own working messages between iterations: it
    // forwards only the system message and the last one, and reads back a history with every tool
    // call and result missing. Enough survives to answer with a single tool, never enough to carry
    // one tool's result into the next call — a run that repeats tools instead of finishing. Keeping
    // the history on makes the loop carry its own messages, whatever the store keeps.
    //
    // Two, so that every run shares one advisor instance and therefore one tool index — see
    // toolCallingAdvisor. ChatClient builds a new one from the builder for every prompt, which is
    // exactly what has to stop, so this registration covers the runs without conversation memory
    // as well: ChatClient skips its own registration once a tool advisor is in the list.
    final var toolAdvisor = toolCallingAdvisor();
    if (toolAdvisor != null) {
      advisors.add(toolAdvisor);
    }
    advisors.add(SimpleLoggerAdvisor.builder().build());

    return chatClient
        .prompt()
        .system(renderedSystemPrompt)
        .user(request.userMessage())
        .tools(composition.tools())
        .toolContext(toolContext)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationId()))
        .advisors(a -> a.param(TOOL_INDEX_KEY, toolIndexKeyFor(request)))
        .advisors(advisors.toArray(new Advisor[0]))
        .stream()
        .chatResponse();
  }

  /** The shared tool advisor, or null where no tool advisor builder is configured. */
  private Advisor toolCallingAdvisor() {
    final var existing = toolCallingAdvisor.get();
    if (existing != null) {
      return existing;
    }
    final var builder = toolCallingAdvisorBuilder.getIfAvailable();
    if (builder == null) {
      return null;
    }
    // Racing callers may each build one; only the first is kept, and the losers are discarded
    // before they have indexed anything.
    final var built = builder.copy().conversationHistoryEnabled(true).build();
    return toolCallingAdvisor.compareAndSet(null, built) ? built : toolCallingAdvisor.get();
  }

  /**
   * Which index the tool search reads and writes, named by {@code
   * spring.ai.chat.client.tool-search-advisor.session-id-key-name}.
   *
   * <p>The user rather than the conversation, because what the index holds is the descriptions of
   * the tools that user's MCP servers offer. Those are the same in every conversation they have, so
   * keying the index per conversation would embed the same few hundred descriptions again for every
   * new thread. The conversation is the fallback only because the advisor rejects a run with no key
   * at all, and a run without a user id has nothing better to be keyed by.
   */
  private static String toolIndexKeyFor(final AgentRequest request) {
    return Strings.isNullOrEmpty(request.userId())
        ? Strings.nullToEmpty(request.conversationId())
        : request.userId();
  }
}
