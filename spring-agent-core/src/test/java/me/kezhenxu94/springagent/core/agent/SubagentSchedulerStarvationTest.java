package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The one thing a subagent must never do to the run that started it: leave it waiting for ever.
 *
 * <p>A run's stream, its tool calls and every subagent's stream all come out of Reactor's shared
 * {@code boundedElastic} pool. That pool is a fixed number of single-threaded executors; a worker
 * is pinned to one of them when it is created, and once the pool is full a new worker is handed one
 * that is already busy — Reactor says so itself: "tasks are pinned to a given executor, so they
 * won't be stolen by an idle executor". So a run that blocks a worker while it waits for a subagent
 * can be holding the very thread that subagent needs in order to finish. Neither ever moves again,
 * and because nothing throws and nothing times out, the turn simply goes quiet.
 *
 * <p>In a deployment that takes a full pool, which one tool-heavy turn is enough to do — Spring
 * AI's streaming tool loop holds a worker per tool-calling round for the whole turn. Here the pool
 * is set to one thread, which turns "sometimes" into "every time" and keeps the test to a single
 * run.
 */
class SubagentSchedulerStarvationTest {

  /** Short enough to keep the test quick; the point is that the wait lets go at all. */
  private static final Duration WAIT_POLL = Duration.ofMillis(200);

  private final AgentToolsProvider agentToolsProvider = mock(AgentToolsProvider.class);
  private final PendingQuestionRepo pendingQuestions = mock(PendingQuestionRepo.class);
  private final ScriptedChatModel chatModel = new ScriptedChatModel();

  /**
   * How every run of a test ended, by request id — the only way to see a subagent's own outcome.
   */
  private final RunOutcomes outcomesByRun = new RunOutcomes();

  private SpringAgent agent;

  /**
   * Once for the class, not once per test: {@code setFactory} shuts the cached schedulers down as
   * it swaps them, so doing it between tests leaves the previous test's operators holding a
   * disposed one. It is the supported way in — the pool size is otherwise a {@code static final}
   * read when {@code Schedulers} is initialised, which a test cannot get ahead of.
   */
  @BeforeAll
  static void oneThreadOnly() {
    Schedulers.setFactory(oneThreadBoundedElastic());
  }

  @AfterAll
  static void restoreSchedulers() {
    Schedulers.resetFactory();
  }

  @BeforeEach
  void setUp() throws Exception {
    final var properties = properties();
    final var messages = messagesIn(Locale.ENGLISH);
    final var chatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(mock(ChatMemoryRepository.class))
            .build();
    agent =
        new SpringAgent(
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            chatMemory,
            properties,
            new Admins(properties),
            agentToolsProvider,
            pendingQuestions,
            messages,
            providerOf(outcomesByRun),
            emptyProvider(),
            emptyProvider(),
            providerOf(ToolCallingAdvisor.builder()));

    // The real tools, not a stand-in: what is under test is how WaitForSubagent waits.
    final var tools = new SubagentTools(agent, properties, messages);
    final var offered =
        Stream.concat(Stream.of(ToolCallbacks.from(tools)), Stream.of(look())).toArray();
    when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            new AgentComposition(offered, List.of(), new McpTools(List.of(), new ToolCallback[0])));
  }

  @Test
  @DisplayName("a run waiting on a subagent still finishes when the elastic pool is down to one")
  void aRunWaitingOnASubagentDoesNotStarveIt() throws Exception {
    final var finished = new CountDownLatch(1);
    final var outcomes = new java.util.concurrent.CopyOnWriteArrayList<AgentOutcome>();

    agent.fire(
        AgentRequest.builder()
            .requestId("req-1")
            .scenario(BuiltInScenarios.CHAT)
            .userId("ou_1")
            .conversationId("om_root")
            .userMessage(user -> user.text("hi"))
            .listener(
                new AgentResponseListener() {
                  @Override
                  public void onFinished(final AgentOutcome outcome) {
                    outcomes.add(outcome);
                    finished.countDown();
                  }
                })
            .build());

    assertThat(finished.await(30, TimeUnit.SECONDS))
        .as("the turn never finished: the run and the subagent it waited for deadlocked")
        .isTrue();
    assertThat(outcomes).containsExactly(AgentOutcome.COMPLETED);
    // Not just "it ended": the answer has to have travelled back through the wait.
    assertThat(chatModel.waitResults).anyMatch(result -> result.contains(SUBAGENT_ANSWER));
  }

  @Test
  @DisplayName("stopping a run that is waiting on its subagents is what releases it")
  void aRunWaitingOnSubagentsCanStillBeCancelled() throws Exception {
    // The run is taken out of liveRuns only once it has finished waiting, and this is why: cancel()
    // finds a run through that map, so removing it first made the stop button do nothing during
    // the one wait a user would ever want to interrupt.
    final var childRunning = new CountDownLatch(1);
    final var finished = new CountDownLatch(1);
    final var outcomes = new java.util.concurrent.CopyOnWriteArrayList<AgentOutcome>();

    // A subagent that says something and then keeps going, so the parent is still waiting when the
    // stop arrives. Started and never waited for, which is the case awaitSubagents covers.
    chatModel.subagentBlocksAfterAnswering(childRunning);
    chatModel.parentStartsWithoutWaiting();

    agent.fire(
        AgentRequest.builder()
            .requestId("req-1")
            .scenario(BuiltInScenarios.CHAT)
            .userId("ou_1")
            .conversationId("om_root")
            .userMessage(user -> user.text("hi"))
            .listener(
                new AgentResponseListener() {
                  @Override
                  public void onFinished(final AgentOutcome outcome) {
                    outcomes.add(outcome);
                    finished.countDown();
                  }
                })
            .build());

    assertThat(childRunning.await(20, TimeUnit.SECONDS)).as("the subagent never ran").isTrue();
    // The parent's own stream is over by now; it is sitting in awaitSubagents.
    assertThat(agent.cancel("req-1")).as("the waiting run could not be found to cancel").isTrue();

    assertThat(finished.await(20, TimeUnit.SECONDS))
        .as("cancelling the run did not release it from waiting on its subagent")
        .isTrue();
    // The parent's own answer was already given, so the turn is reported for what it did — what
    // the cancel stops is the subagent still running underneath it, and that is what let the
    // parent go.
    assertThat(outcomes).containsExactly(AgentOutcome.COMPLETED);
    assertThat(outcomesByRun.subagent()).isEqualTo(AgentOutcome.CANCELLED);
  }

  private static final String SUBAGENT_ANSWER = "the timeline starts on Monday";

  /**
   * A bean listener, the way an integration takes part in every run: it is told the run's id up
   * front and hangs a listener of its own on it, which is the only place a subagent's outcome can
   * be seen from outside.
   */
  private static final class RunOutcomes implements AgentResponseListener {
    private final java.util.Map<String, AgentOutcome> byRequestId =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onStart(final AgentRunRegistry registry) {
      final var requestId = registry.request().requestId();
      registry.addResponseListener(
          new AgentResponseListener() {
            @Override
            public void onFinished(final AgentOutcome outcome) {
              byRequestId.put(requestId, outcome);
            }
          });
    }

    /** The one run whose id was minted rather than given by the test. */
    private AgentOutcome subagent() {
      return byRequestId.entrySet().stream()
          .filter(entry -> entry.getKey().startsWith("sub_"))
          .map(java.util.Map.Entry::getValue)
          .findFirst()
          .orElse(null);
    }
  }

  /** Something for a subagent to do, so that its run needs a tool round of its own. */
  private static ToolCallback look() {
    final var definition =
        org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name("Look")
            .description("Look at the thing")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
    return new ToolCallback() {
      @Override
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "looked";
      }

      @Override
      public String call(
          final String toolInput, final org.springframework.ai.chat.model.ToolContext toolContext) {
        return "looked";
      }
    };
  }

  /** A pool of exactly one single-threaded executor, whatever Reactor was asked for. */
  private static Schedulers.Factory oneThreadBoundedElastic() {
    return new Schedulers.Factory() {
      @Override
      public Scheduler newBoundedElastic(
          final int threadCap,
          final int queuedTaskCap,
          final ThreadFactory threadFactory,
          final int ttlSeconds) {
        return Schedulers.Factory.super.newBoundedElastic(
            1, queuedTaskCap, threadFactory, ttlSeconds);
      }
    };
  }

  /**
   * Plays the parent turn — start a subagent, wait for it, answer — and answers the subagent's own
   * run with the line the parent is supposed to read back.
   */
  private static final class ScriptedChatModel implements ChatModel {
    private static final Pattern SUBAGENT_ID = Pattern.compile("sub_[0-9a-f]{8}");

    /** What each WaitForSubagent call handed back, which is what the parent actually read. */
    private final List<String> waitResults = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Set to leave the subagent running rather than let it end, so the parent is caught waiting.
     */
    private volatile CountDownLatch subagentReached;

    /** Set to make the parent start a subagent and finish its turn without collecting it. */
    private volatile boolean parentDoesNotWait;

    void subagentBlocksAfterAnswering(final CountDownLatch reached) {
      this.subagentReached = reached;
    }

    void parentStartsWithoutWaiting() {
      this.parentDoesNotWait = true;
    }

    @Override
    public ToolCallingChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
      throw new UnsupportedOperationException("the agent only streams");
    }

    @Override
    public Flux<ChatResponse> stream(final Prompt prompt) {
      if (isSubagent(prompt)) {
        final var reached = subagentReached;
        if (reached != null) {
          // Says it is here and then goes on saying it, so the only thing that ends it is the
          // cancel travelling down from the run that started it. Still emitting rather than idle
          // because that is how a run learns it was cancelled: the flag is read on the next
          // emission, so a subagent that fell silent could not be stopped at all.
          return Flux.<ChatResponse>just(text(SUBAGENT_ANSWER))
              .concatWith(
                  Flux.interval(Duration.ofMillis(50))
                      .map(tick -> text(SUBAGENT_ANSWER))
                      .doOnSubscribe(subscription -> reached.countDown()));
        }
        // A subagent that does real work calls a tool, and that is what makes this a deadlock
        // rather than a delay: its tool round needs a worker of its own, picked after the parent
        // has queued the round it will block in, so it can be sitting behind that block for ever.
        return lastToolResult(prompt) == null
            ? Flux.just(toolCall("Look", "{}"))
            : Flux.just(text(SUBAGENT_ANSWER));
      }
      final var lastToolResult = lastToolResult(prompt);
      if (lastToolResult == null) {
        return Flux.just(
            toolCall("StartSubagent", "{\"description\":\"reading\",\"prompt\":\"read it\"}"));
      }
      if (parentDoesNotWait) {
        return Flux.just(text("started it, not waiting"));
      }
      final var id = SUBAGENT_ID.matcher(lastToolResult);
      if (!id.find()) {
        // Neither a start nor an answer carrying an id: nothing left to wait for, so answer.
        return Flux.just(text("done"));
      }
      if (lastToolResult.contains(SUBAGENT_ANSWER)) {
        waitResults.add(lastToolResult);
        return Flux.just(text("done"));
      }
      if (lastToolResult.contains("still working")) {
        waitResults.add(lastToolResult);
      }
      return Flux.just(toolCall("WaitForSubagent", "{\"subagentId\":\"" + id.group() + "\"}"));
    }

    /** A subagent's run is the one whose user message is the brief, not the user's message. */
    private static boolean isSubagent(final Prompt prompt) {
      return prompt.getInstructions().stream()
          .map(Message::getText)
          .anyMatch(text -> text != null && text.contains("You are running as a subagent"));
    }

    private static String lastToolResult(final Prompt prompt) {
      return prompt.getInstructions().stream()
          .filter(ToolResponseMessage.class::isInstance)
          .map(ToolResponseMessage.class::cast)
          .flatMap(message -> message.getResponses().stream())
          .map(ToolResponseMessage.ToolResponse::responseData)
          .reduce((first, second) -> second)
          .orElse(null);
    }

    private static ChatResponse toolCall(final String name, final String arguments) {
      return new ChatResponse(
          List.of(
              new Generation(
                  AssistantMessage.builder()
                      .content("")
                      .toolCalls(
                          List.of(
                              new AssistantMessage.ToolCall(
                                  "call-" + name, "function", name, arguments)))
                      .build())));
    }

    private static ChatResponse text(final String content) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            new SpringAgentProperties.Ai.Tools(
                null,
                new SpringAgentProperties.Ai.Tools.Subagent(3, WAIT_POLL, null),
                null,
                null,
                null),
            "You are {userId}. Format: {replyFormat}",
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }

  private static CoreMessages messagesIn(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale, null, null));
  }

  private static <T> ObjectProvider<T> emptyProvider() {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        throw new IllegalStateException("none");
      }

      @Override
      public Stream<T> stream() {
        return Stream.empty();
      }

      @Override
      public void ifAvailable(final Consumer<T> action) {}
    };
  }

  private static <T> ObjectProvider<T> providerOf(final T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public Stream<T> stream() {
        return Stream.of(value);
      }

      @Override
      public void ifAvailable(final Consumer<T> action) {
        action.accept(value);
      }
    };
  }
}
