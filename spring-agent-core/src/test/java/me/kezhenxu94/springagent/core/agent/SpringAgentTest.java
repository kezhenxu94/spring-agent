package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.tools.QuestionNotAnsweredException;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallback;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallbackResolver;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import reactor.core.publisher.Flux;

/**
 * Covers what {@link SpringAgent#fire} took over from its callers: assembling the run's identity,
 * putting questions to every channel, and releasing the MCP clients exactly once however the run
 * ends.
 */
class SpringAgentTest {

  @TempDir Path memoriesDirectory;

  private final McpSyncClient mcpClient = mock(McpSyncClient.class);
  private final AgentToolsProvider agentToolsProvider = mock(AgentToolsProvider.class);
  private final RecordingChatModel chatModel = new RecordingChatModel();
  private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);
  private final PendingQuestionRepo pendingQuestions = mock(PendingQuestionRepo.class);

  /** Stands in for an integration taking part in a run it did not initiate. */
  private AgentResponseListener declaredListener = new AgentResponseListener() {};

  /** Stands in for a surface saying how it wants an answer written. */
  private PromptVariablesContributor declaredContributor = request -> Map.of();

  private SpringAgent agent;

  @BeforeEach
  void setUp() throws Exception {
    when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            new AgentComposition(
                new Object[0],
                autoMemoryAdvisors(),
                new McpTools(List.of(mcpClient), new ToolCallback[0])));
    final var chatMemory =
        MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
    agent =
        new SpringAgent(
            // Real model options (OpenAI's) are ToolCallingChatOptions, which is the only kind
            // that carries a tool context; the plain default ones would silently drop it.
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            chatMemory,
            properties(),
            new Admins(properties()),
            agentToolsProvider,
            pendingQuestions,
            messagesIn(Locale.ENGLISH),
            listenerProvider(),
            contributorProvider(),
            // Present, as on a JPA or MongoDB deployment; Redis has no such bean.
            recorderProvider(new AskedQuestionsRecorder(chatMemory, messagesIn(Locale.ENGLISH))),
            // The plain advisor rather than the tool-search one an application configures: what is
            // under test here is the conversation history the agent turns back on, which both
            // carry the same way.
            providerOf(ToolCallingAdvisor.builder()));
  }

  @Test
  @DisplayName("the run's own identity wins over tool context a bean listener contributed")
  void requestIdentityWinsOverContributedToolContext() {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addToolContext(ToolContexts.KEY_USER_ID, "hijacked");
            registry.addToolContext("cardUpdater", "the-updater");
          }
        };

    fireAndAwait(request());

    final var toolContext = chatModel.lastToolContext();
    assertThat(toolContext).containsEntry(ToolContexts.KEY_USER_ID, "ou_1");
    assertThat(toolContext).containsEntry(ToolContexts.KEY_CHAT_ID, "oc_1");
    assertThat(toolContext).containsEntry(ToolContexts.KEY_ROOT_MESSAGE_ID, "om_root");
    assertThat(toolContext).containsEntry(ToolContexts.KEY_REPLY_MESSAGE_ID, "om_reply");
    // Everything else a listener contributed still reaches the tools.
    assertThat(toolContext).containsEntry("cardUpdater", "the-updater");
  }

  @Test
  @DisplayName("chatType defaults so an integration without the notion need not invent one")
  void chatTypeDefaults() {
    fireAndAwait(request().chatType(null));

    assertThat(chatModel.lastToolContext()).containsEntry(ToolContexts.KEY_CHAT_TYPE, "p2p");
  }

  @Test
  @DisplayName("a surface's own rules reach the prompt of a run it did not start")
  void contributedPromptVariablesReachThePrompt() {
    declaredContributor = request -> Map.of("replyFormat", "mention with <at>");

    fireAndAwait(request());

    assertThat(systemPrompt()).contains("Format: mention with <at>");
  }

  @Test
  @DisplayName("what the run itself says wins over what the surface says in general")
  void requestVariablesWinOverContributedOnes() {
    declaredContributor = request -> Map.of("replyFormat", "in general");

    fireAndAwait(request().promptVariables(Map.of("replyFormat", "for this run")));

    assertThat(systemPrompt()).contains("Format: for this run").doesNotContain("in general");
  }

  @Test
  @DisplayName("a contributor that throws costs the run its formatting, not its answer")
  void aBrokenContributorDoesNotFailTheRun() {
    declaredContributor =
        request -> {
          throw new IllegalStateException("cannot say");
        };

    final var listener = fireAndAwait(request());

    assertThat(listener.outcomes).containsExactly(AgentOutcome.COMPLETED);
    assertThat(systemPrompt()).contains("Format: ");
  }

  /** What a subagent of these tests is sent, and how its own responses are recognised. */
  private static final String SUBAGENT_MESSAGE = "do the reading";

  @Test
  @DisplayName("a run is not reported finished while a subagent of its own is still running")
  void aRunWaitsForItsSubagents() {
    final var ended = new java.util.concurrent.CopyOnWriteArrayList<String>();
    // Outlives its parent, whose own stream is over the moment the tool call returns.
    final var child =
        subagentListener(ended, "child", listener -> sleep(java.time.Duration.ofMillis(300)));
    toolThatStartsASubagent(child, false);
    chatModel.callToolOnce("StartSubagent");

    fireAndAwait(request().listener(recordsEnd(ended, "parent")));

    assertThat(ended).containsExactly("child", "parent");
  }

  @Test
  @DisplayName("what a subagent spends is counted on the turn that asked for it")
  void subagentTokensAreCountedOnTheParent() {
    chatModel.reportUsage();
    final var models = new java.util.concurrent.CopyOnWriteArrayList<String>();
    toolThatStartsASubagent(new AgentResponseListener() {}, true);
    chatModel.callToolOnce("StartSubagent");

    fireAndAwait(
        request()
            .listener(
                new AgentResponseListener() {
                  @Override
                  public void onUsage(
                      final String model, final org.springframework.ai.chat.metadata.Usage usage) {
                    models.add(model);
                  }
                }));

    // Its own and the subagent's, on the one listener: a card counting the turn counts both.
    assertThat(models).contains("parent-model", "child-model");
  }

  @Test
  @DisplayName("stopping a run stops the subagents it is waiting on")
  void cancellingARunCancelsItsSubagents() {
    final var outcomes = new java.util.concurrent.CopyOnWriteArrayList<AgentOutcome>();
    // Stops the whole turn part-way through the subagent's own answer, as the stop button does.
    final var child =
        new AgentResponseListener() {
          @Override
          public void onContent(final String contentSoFar) {
            agent.cancel("req-1");
          }

          @Override
          public void onFinished(final AgentOutcome outcome) {
            outcomes.add(outcome);
          }
        };
    toolThatStartsASubagent(child, true);
    chatModel.callToolOnce("StartSubagent");

    final var listener = fireAndAwait(request());

    assertThat(outcomes).containsExactly(AgentOutcome.CANCELLED);
    assertThat(listener.outcomes).containsExactly(AgentOutcome.CANCELLED);
  }

  /**
   * Composes one tool that does what {@code StartSubagent} does: fires a run naming this one as its
   * parent, and either waits for it or leaves it running.
   */
  private void toolThatStartsASubagent(
      final AgentResponseListener childListener, final boolean waitForIt) {
    final var childFinished = new CountDownLatch(1);
    final var callback =
        callbackThat(
            () -> {
              agent.fire(
                  AgentRequest.builder()
                      .requestId("sub-1")
                      .parentRequestId("req-1")
                      .description("reading the timeline")
                      .scenario(BuiltInScenarios.SUBAGENT)
                      .userId("ou_1")
                      .conversationId("sub-1")
                      .userMessage(user -> user.text(SUBAGENT_MESSAGE))
                      .listener(childListener)
                      .listener(
                          new AgentResponseListener() {
                            @Override
                            public void onFinished(final AgentOutcome outcome) {
                              childFinished.countDown();
                            }
                          })
                      .build());
              if (waitForIt) {
                try {
                  childFinished.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              return "sub-1";
            });
    try {
      when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
          .thenReturn(
              new AgentComposition(
                  new Object[] {callback},
                  autoMemoryAdvisors(),
                  new McpTools(List.of(mcpClient), new ToolCallback[0])));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /** A tool named {@code StartSubagent} that does whatever {@code action} does and returns it. */
  private static ToolCallback callbackThat(final java.util.function.Supplier<String> action) {
    final var definition =
        ToolDefinition.builder()
            .name("StartSubagent")
            .description("StartSubagent")
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return action.get();
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return action.get();
      }
    };
  }

  /** Records that a run ended, under the name given, in the order the runs end. */
  private static AgentResponseListener recordsEnd(final List<String> ended, final String name) {
    return new AgentResponseListener() {
      @Override
      public void onFinished(final AgentOutcome outcome) {
        ended.add(name);
      }
    };
  }

  /** {@link #recordsEnd} plus something to do while the run is going. */
  private static AgentResponseListener subagentListener(
      final List<String> ended, final String name, final Consumer<Void> whileRunning) {
    return new AgentResponseListener() {
      @Override
      public void onContent(final String contentSoFar) {
        whileRunning.accept(null);
      }

      @Override
      public void onFinished(final AgentOutcome outcome) {
        ended.add(name);
      }
    };
  }

  private static void sleep(final java.time.Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The system message of the first call, which is where the rendered prompt lands. */
  private String systemPrompt() {
    assertThat(chatModel.prompts).as("the model was never called").isNotEmpty();
    return chatModel.prompts.get(0).getInstructions().stream()
        .filter(SystemMessage.class::isInstance)
        .map(Message::getText)
        .collect(Collectors.joining());
  }

  @Test
  @DisplayName("a run that completes reports COMPLETED and releases its MCP clients")
  void completedRun() {
    final var listener = fireAndAwait(request());

    assertThat(listener.outcomes).containsExactly(AgentOutcome.COMPLETED);
    assertThat(listener.contents).containsExactly("hello ", "hello world");
    verify(mcpClient, timeout(5000).times(1)).close();
  }

  @Test
  @DisplayName("a run whose model fails reports FAILED without leaking MCP clients")
  void failedRun() {
    chatModel.failWith(new IllegalStateException("model is down"));

    final var listener = fireAndAwait(request());

    assertThat(listener.errors).hasSize(1);
    // The advisor chain wraps whatever the model threw, so the cause is what identifies it.
    assertThat(listener.errors.getFirst()).hasRootCauseMessage("model is down");
    assertThat(listener.outcomes).containsExactly(AgentOutcome.FAILED);
    verify(mcpClient, timeout(5000).times(1)).close();
  }

  @Test
  @DisplayName("a run that cannot be composed reports FAILED rather than failing silently")
  void compositionFailureRun() throws Exception {
    when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
        .thenThrow(new IOException("no workspace"));

    final var listener = fireAndAwait(request());

    assertThat(listener.errors).hasSize(1);
    assertThat(listener.errors.getFirst()).hasMessage("no workspace");
    assertThat(listener.outcomes).containsExactly(AgentOutcome.FAILED);
  }

  @Test
  @DisplayName("a listener that stops consuming ends the run as CANCELLED, still releasing clients")
  void cancelledRun() {
    final var listener = new RecordingListener();
    listener.stopAfterFirstContent = true;

    fireAndAwait(request(), listener);

    assertThat(listener.contents).containsExactly("hello ");
    assertThat(listener.outcomes).containsExactly(AgentOutcome.CANCELLED);
    verify(mcpClient, timeout(5000).times(1)).close();
  }

  @Test
  @DisplayName("a listener that aborts the run stops it before the model is ever contacted")
  void abortedRun() throws Exception {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.abort("nowhere to put the answer");
          }
        };

    final var listener = fireAndAwait(request());

    assertThat(listener.outcomes).containsExactly(AgentOutcome.FAILED);
    assertThat(listener.errors).hasSize(1);
    assertThat(listener.errors.getFirst()).hasMessage("nowhere to put the answer");
    verify(agentToolsProvider, times(0)).compose(any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  @DisplayName("a listener that fails on the way out still does not cost the run its cleanup")
  void cleanupSurvivesAFailingListener() {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onFinished(final AgentOutcome outcome) {
            // An Error, not an Exception: NoClassDefFoundError on this path is what showed that
            // cleanup was skippable, and per-listener guarding only catches Exception.
            throw new NoClassDefFoundError("boom");
          }
        };

    fireAndAwait(request());

    // Closing the clients is the first half of the same finally block that decrements the in-flight
    // count, which shutdown waits on.
    verify(mcpClient, timeout(5000).times(1)).close();
  }

  @Test
  @DisplayName("whatever the channel answered with reaches the model unchanged")
  void answersReachTheModelUnchanged() throws Exception {
    assertThat(askIn(request())).containsEntry("Which database should we use?", "not answered yet");
  }

  @Test
  @DisplayName("every registered handler is given the questions, not just the first")
  void everyHandlerIsAsked() throws Exception {
    final var second = new RecordingQuestionHandler("second");
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(new RecordingQuestionHandler("first"));
            registry.addQuestionHandler(second);
          }
        };
    fireAndAwait(request());

    handlerFromRun().handle(questions());

    assertThat(second.asked).hasSize(1);
  }

  @Test
  @DisplayName("a handler that throws does not cost the ask the channels that worked")
  void oneBrokenHandlerDoesNotStopTheOthers() throws Exception {
    final var working = new RecordingQuestionHandler("working");
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> {
                  throw new IllegalStateException("this channel is down");
                });
            registry.addQuestionHandler(working);
          }
        };
    fireAndAwait(request());

    final var answers = handlerFromRun().handle(questions());

    assertThat(working.asked).hasSize(1);
    assertThat(answers).containsValue("working");
  }

  @Test
  @DisplayName("an ask nobody answered leaves a note the next run can read back")
  void unansweredAskIsRecorded() throws Exception {
    // What a later run reads instead of the tool call JdbcChatMemoryRepository drops.
    fireAndAwait(unansweredAsk());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class);

    assertThat(savedText())
        .anySatisfy(
            text ->
                assertThat(text)
                    .contains("now in front of the user")
                    // The questions are not repeated: the conversation being replayed carries them.
                    .doesNotContain("Which database should we use?"));
  }

  @Test
  @DisplayName("an answered ask leaves no note telling a later run to wait for the answer")
  void answeredAskIsNotRecorded() throws Exception {
    // The note says to wait rather than ask again, which is untrue once the answer is in hand.
    assertThat(askIn(request())).containsEntry("Which database should we use?", "not answered yet");

    assertThat(savedText())
        .noneSatisfy(text -> assertThat(text).contains("now in front of the user"));
  }

  @Test
  @DisplayName("an ask that reached nobody leaves no note claiming it did")
  void refusedAskIsNotRecorded() throws Exception {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> {
                  throw new IllegalStateException("this channel is down");
                });
          }
        };
    fireAndAwait(request());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class);

    assertThat(savedText())
        .noneSatisfy(text -> assertThat(text).contains("now in front of the user"));
  }

  @Test
  @DisplayName("a scheduled task's unanswered ask is noted like any other run's")
  void askingIsRecordedForAScheduledTask() throws Exception {
    // A firing shares the conversation of the thread the task was created in, so the note reaches
    // the user's own history as well as the next firing's.
    fireAndAwait(unansweredAsk().scenario(BuiltInScenarios.SCHEDULED_TASK));

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class);

    assertThat(savedText())
        .anySatisfy(text -> assertThat(text).contains("now in front of the user"));
  }

  @Test
  @DisplayName("a run with no conversation has nothing to leave the note in")
  void askingIsNotRecordedWithoutAConversation() throws Exception {
    fireAndAwait(unansweredAsk().conversationId(null));

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class);

    assertThat(savedText())
        .noneSatisfy(text -> assertThat(text).contains("now in front of the user"));
  }

  /** A run whose one channel puts the questions up and comes back with nothing. */
  private AgentRequest.AgentRequestBuilder unansweredAsk() {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(questions -> Map.of());
          }
        };
    return request();
  }

  /** The text of every message saved to the conversation over the whole test. */
  @SuppressWarnings("unchecked")
  private List<String> savedText() {
    final var saved = ArgumentCaptor.forClass(List.class);
    verify(chatMemoryRepository, atLeast(0)).saveAll(any(), saved.capture());
    return ((List<List<Message>>) (List<?>) saved.getAllValues())
        .stream().flatMap(List::stream).map(Message::getText).toList();
  }

  @Test
  @DisplayName("an ask no channel can answer within the call ends the turn and skips validation")
  void askEndsTheTurnWhenNoChannelAnswersWithinTheCall() throws Exception {
    // returnDirect: Spring AI hands the result to the application instead of the model, so the run
    // stops without the model having to stop itself — which it does not reliably do. The same flag
    // turns off the answer-per-question check, which an ask that comes back empty cannot pass.
    fireAndAwait(unansweredAsk());

    assertThat(answersArriveLaterFromRun()).isTrue();
  }

  @Test
  @DisplayName("a channel that answers within the call keeps the turn going and is validated")
  void askDoesNotEndTheTurnForASynchronousChannel() throws Exception {
    // Ending it would throw away the answer the channel just collected, and an answer is expected
    // here, so the tool's own check that every question got one is worth keeping.
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(new SynchronousRecordingHandler());
          }
        };
    fireAndAwait(request());

    assertThat(answersArriveLaterFromRun()).isFalse();
  }

  @Test
  @DisplayName("what the user reads when the turn ends at the ask is the question's own header")
  void headersAreWhatTheDirectReturnCarries() throws Exception {
    // The model never reads this: the result goes to the application, and from there onto the card
    // above the form. So it is the model's short label for the question, not an instruction to it.
    fireAndAwait(unansweredAsk());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class)
        .hasMessage("Database");
  }

  /** What the run told compose() about whether an answer can arrive inside the call. */
  private boolean answersArriveLaterFromRun() throws Exception {
    final var endsTurn = ArgumentCaptor.forClass(Boolean.class);
    verify(agentToolsProvider).compose(any(), any(), any(), any(), endsTurn.capture(), any());
    return endsTurn.getValue();
  }

  /** Synchronous, but this time with nothing to show for it. */
  private static final class EmptySynchronousHandler
      implements QuestionHandler, SynchronousQuestionHandler {
    @Override
    public Map<String, String> handle(final List<Question> questions) {
      return Map.of();
    }
  }

  /** A channel of the command line's kind: the answer arrives inside the call. */
  private static final class SynchronousRecordingHandler
      implements QuestionHandler, SynchronousQuestionHandler {
    @Override
    public Map<String, String> handle(final List<Question> questions) {
      final var answers = new LinkedHashMap<String, String>();
      questions.forEach(question -> answers.put(question.question(), "cli"));
      return answers;
    }
  }

  @Test
  @DisplayName("no channel managing to ask tells the model so")
  void noHandlerPresenting() throws Exception {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> {
                  throw new IllegalStateException("this channel is down");
                });
          }
        };
    fireAndAwait(request());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class)
        .hasMessageStartingWith("COULD NOT ASK");
  }

  @Test
  @DisplayName("an ask that is out but unanswered tells the model to end its turn")
  void presentedButUnanswered() throws Exception {
    // A channel that answers within the call, so the turn carries on and the model is the one
    // reading this. Where the turn ends at the ask instead, the user reads it — see the headers
    // test below.
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(new EmptySynchronousHandler());
          }
        };
    fireAndAwait(request());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class)
        .hasMessageContaining("now in front of the user");
  }

  @Test
  @DisplayName("a channel settling the ask with its own note still leaves the others their turn")
  void handlerNoteDoesNotSkipTheChannelsAfterIt() throws Exception {
    final var afterwards = new RecordingQuestionHandler("afterwards");
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> {
                  throw new QuestionNotAnsweredException("THE USER DISMISSED IT");
                });
            registry.addQuestionHandler(afterwards);
          }
        };
    fireAndAwait(request());

    // The second channel answered, and a real answer beats the first channel's note.
    assertThat(handlerFromRun().handle(questions())).containsValue("afterwards");
    assertThat(afterwards.asked).hasSize(1);
  }

  @Test
  @DisplayName("a channel's own note is what the model reads when no channel came back with one")
  void handlerNoteIsNotSwallowed() throws Exception {
    final var quiet = new ArrayList<List<Question>>();
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> {
                  throw new QuestionNotAnsweredException("THE USER DISMISSED IT");
                });
            registry.addQuestionHandler(
                questions -> {
                  quiet.add(questions);
                  return Map.of();
                });
          }
        };
    fireAndAwait(request());

    final var handler = handlerFromRun();
    assertThatThrownBy(() -> handler.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class)
        .hasMessage("THE USER DISMISSED IT");
    assertThat(quiet).hasSize(1);
  }

  @Test
  @DisplayName("questions already waiting in the conversation are not put up a second time")
  void outstandingAskIsNotRepeated() throws Exception {
    final var handler = new RecordingQuestionHandler("feishu");
    when(pendingQuestions.findByConversationIdAndStatus(any(), eq(PendingQuestion.Status.PENDING)))
        .thenReturn(List.of(new PendingQuestion()));
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(handler);
          }
        };
    fireAndAwait(request());

    final var composed = handlerFromRun();
    assertThatThrownBy(() -> composed.handle(questions()))
        .isInstanceOf(QuestionNotAnsweredException.class)
        .hasMessageStartingWith("ALREADY ASKED");
    assertThat(handler.asked).isEmpty();
  }

  @Test
  @DisplayName("the note a handler throws is what the model reads as the tool's result")
  void noteReachesTheModelAsTheToolResult() {
    // The whole design rests on this, so it is driven through the manager
    // SpringAgentCoreAutoConfiguration actually builds rather than the exception processor alone.
    // A library upgrade, or a spring.ai.tools.throw-exception-on-error naming this type, breaks
    // the ask silently.
    final var asked = new AtomicInteger();
    final var tool =
        AskUserQuestionTool.builder()
            .answersValidation(false)
            .questionHandler(
                questions -> {
                  asked.incrementAndGet();
                  throw new QuestionNotAnsweredException("NOT ANSWERED YET. End your turn.");
                })
            .build();
    final var manager = managerResolvingNothingByName();

    final var history = executeAsk(manager, ToolCallbacks.from(tool));

    assertThat(history).containsExactly("NOT ANSWERED YET. End your turn.");
    // Once, not once per interceptor or per round of the manager.
    assertThat(asked).hasValue(1);
  }

  @Test
  @DisplayName("a tool that ends the turn still ends it after the interceptors have wrapped it")
  void returnDirectSurvivesTheInterceptingWrapper() {
    // InterceptingToolCallingManager wraps every callback a run carries, and ToolCallback defaults
    // returnDirect to off: a wrapper that does not forward the metadata silently turns a tool that
    // ends the turn into one that does not, which is invisible until a model declines to stop.
    final var tool =
        AskUserQuestionTool.builder()
            .answersValidation(false)
            .questionHandler(questions -> Map.of())
            .build();
    final var endsTurn =
        new InterceptingToolCallback(
            new EndsTurnCallback(ToolCallbacks.from(tool)[0]), List.of(), noFileRefs());

    assertThat(endsTurn.getToolMetadata().returnDirect()).isTrue();
  }

  /** Stands in for the ask as AgentToolsProvider offers it when the turn ends there. */
  private record EndsTurnCallback(ToolCallback delegate) implements ToolCallback {
    @Override
    public ToolDefinition getToolDefinition() {
      return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
      return ToolMetadata.builder().returnDirect(true).build();
    }

    @Override
    public String call(final String toolInput) {
      return delegate.call(toolInput);
    }
  }

  @Test
  @DisplayName("an ask left out of an iteration's callbacks is not put to the user at all")
  void askMissingFromTheIterationIsNotPresented() {
    // The tool-search advisor rebuilds the callbacks every iteration, and AskUserQuestionTool is a
    // per-request tool no resolver can find by name. The model then reads the resolver's recovery
    // message, which tells it to call the tool again — and no question reaches the user.
    final var manager = managerResolvingNothingByName();

    final var history = executeAsk(manager, new ToolCallback[0]);

    assertThat(history).singleElement(as(STRING)).contains("was not offered to this call");
  }

  /**
   * A manager wired as {@code SpringAgentCoreAutoConfiguration} wires the real one, over a delegate
   * resolver that finds nothing: the flag has to be set here too, since without it Spring AI never
   * consults the resolver at all and these tests would exercise a path the application does not
   * take.
   */
  private static ToolCallingManager managerResolvingNothingByName() {
    return new InterceptingToolCallingManager(
        DefaultToolCallingManager.builder()
            .toolCallbackResolver(
                new InterceptingToolCallbackResolver(toolName -> null, List.of(), noFileRefs()))
            .build(),
        List.of(),
        noFileRefs());
  }

  /** The tool responses one AskUserQuestionTool call produces, as the model would read them. */
  private static List<String> executeAsk(
      final ToolCallingManager manager, final ToolCallback[] callbacks) {
    final var toolCall =
        new AssistantMessage.ToolCall("call_1", "function", "AskUserQuestionTool", toolInput());
    final var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
    final var prompt =
        new Prompt(
            List.of(new UserMessage("hi")),
            ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());

    final var last = manager.executeToolCalls(prompt, response).conversationHistory().getLast();
    assertThat(last).isInstanceOf(ToolResponseMessage.class);
    return ((ToolResponseMessage) last)
        .getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData).toList();
  }

  private static String toolInput() {
    return """
    {"questions":[{"question":"Which database should we use?","header":"Database",\
    "options":[{"label":"Postgres","description":"The one we already run"},\
    {"label":"MySQL","description":"The one we do not"}],"multiSelect":false}]}\
    """;
  }

  /** Fires a run that registers a question handler, then asks through the handler it composed. */
  private Map<String, String> askIn(final AgentRequest.AgentRequestBuilder request)
      throws Exception {
    declaredListener =
        new AgentResponseListener() {
          @Override
          public void onStart(final AgentRunRegistry registry) {
            registry.addQuestionHandler(
                questions -> Map.of(questions.getFirst().question(), "not answered yet"));
          }
        };
    fireAndAwait(request);

    final var composed = ArgumentCaptor.forClass(QuestionHandler.class);
    verify(agentToolsProvider)
        .compose(any(), any(), any(), composed.capture(), anyBoolean(), any());
    return composed
        .getValue()
        .handle(
            List.of(new Question("Which database should we use?", "Database", List.of(), false)));
  }

  /** The one handler the run composed out of everything registered. */
  private QuestionHandler handlerFromRun() throws Exception {
    final var composed = ArgumentCaptor.forClass(QuestionHandler.class);
    verify(agentToolsProvider)
        .compose(any(), any(), any(), composed.capture(), anyBoolean(), any());
    return composed.getValue();
  }

  private static List<Question> questions() {
    return List.of(new Question("Which database should we use?", "Database", List.of(), false));
  }

  @Test
  @DisplayName("a request arriving during shutdown is dropped, and said to have been")
  void droppedDuringShutdown() throws Exception {
    final var listener = new RecordingListener();
    agent.onShutdown();

    agent.fire(request().listener(listener).build());

    // Nothing is assembled and the model is never called — but it is still reported: whoever waits
    // for the run to end is fed by onFinished and nothing else, and silence here is a caller that
    // waits for a run that is not coming.
    assertThat(listener.finished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(listener.outcomes).containsExactly(AgentOutcome.FAILED);
    assertThat(listener.errors).hasSize(1);
    verify(agentToolsProvider, times(0)).compose(any(), any(), any(), any(), anyBoolean(), any());
  }

  private RecordingListener fireAndAwait(final AgentRequest.AgentRequestBuilder request) {
    return fireAndAwait(request, new RecordingListener());
  }

  /** The run reports back off the calling thread, so every assertion waits for it to end. */
  private RecordingListener fireAndAwait(
      final AgentRequest.AgentRequestBuilder request, final RecordingListener listener) {
    agent.fire(request.listener(listener).build());
    try {
      assertThat(listener.finished.await(10, TimeUnit.SECONDS))
          .as("the run did not finish in time")
          .isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    return listener;
  }

  /** The bean listener under test, resolved the way Spring resolves the whole set of them. */
  private static ObjectProvider<AskedQuestionsRecorder> recorderProvider(
      final AskedQuestionsRecorder recorder) {
    return new ObjectProvider<>() {
      @Override
      public AskedQuestionsRecorder getObject() {
        return recorder;
      }

      @Override
      public void ifAvailable(final Consumer<AskedQuestionsRecorder> action) {
        action.accept(recorder);
      }
    };
  }

  /** A bean that is there, resolved the way Spring resolves an optional one. */
  private static <T> ObjectProvider<T> providerOf(final T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public void ifAvailable(final Consumer<T> action) {
        action.accept(value);
      }
    };
  }

  private ObjectProvider<AgentResponseListener> listenerProvider() {
    return new ObjectProvider<>() {
      @Override
      public AgentResponseListener getObject() {
        return declaredListener;
      }

      @Override
      public Stream<AgentResponseListener> stream() {
        return Stream.of(declaredListener);
      }
    };
  }

  private ObjectProvider<PromptVariablesContributor> contributorProvider() {
    return new ObjectProvider<>() {
      @Override
      public PromptVariablesContributor getObject() {
        return declaredContributor;
      }

      @Override
      public Stream<PromptVariablesContributor> stream() {
        return Stream.of(declaredContributor);
      }
    };
  }

  private static AgentRequest.AgentRequestBuilder request() {
    return AgentRequest.builder()
        .requestId("req-1")
        .scenario(BuiltInScenarios.CHAT)
        .userId("ou_1")
        .chatId("oc_1")
        .chatType("group")
        .conversationId("om_root")
        .rootMessageId("om_root")
        .replyMessageId("om_reply")
        .userMessage(user -> user.text("hi"));
  }

  /** What the real provider composes: the auto-memory tools, delivered as an advisor. */
  private List<Advisor> autoMemoryAdvisors() {
    return List.of(
        AutoMemoryToolsAdvisor.builder()
            .memoriesRootDirectory(memoriesDirectory.toString())
            .build());
  }

  /** Core's notes, through a message source configured as an application's would be. */
  private static CoreMessages messagesIn(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale, null));
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            Set.of(),
            Map.of(),
            null,
            null,
            null,
            "You are {userId} in {chatId} ({chatType}), thread {threadId}, parent {parentId},"
                + " mentions {mentions}. Format: {replyFormat}",
            null,
            null),
        Locale.ENGLISH,
        null);
  }

  @Test
  @DisplayName(
      "a message arriving mid-run joins the turn as a user message, after the tool results")
  void aMessageArrivingMidRunJoinsTheTurn() {
    // Through the manager the application actually wires, since that is where the message joins the
    // turn: the plain manager Spring AI builds by itself would leave the queue unread and the whole
    // arrangement would silently fall back to answering the message in a run of its own.
    agentWithInterceptingManager(
        toolCallback("SearchRepository", "no repository called spring-agenk"));
    chatModel.callToolOnce("SearchRepository");

    final var listener = new RecordingListener();
    // Queued from inside the tool call, which is where a real one arrives from: the run is going
    // and
    // is between model calls.
    queueDuringTheToolCall("it should be kezhenxu94/spring-agent");

    fireAndAwait(request().listener(listener));

    assertThat(chatModel.prompts).as("the loop never came back for a second call").hasSize(2);
    final var second = chatModel.prompts.get(1).getInstructions();
    assertThat(second)
        .last()
        .as("the message has to come after the tool results, as the turn's newest message")
        .isInstanceOfSatisfying(
            UserMessage.class,
            user -> assertThat(user.getText()).contains("it should be kezhenxu94/spring-agent"));
    // The tool call it arrived during is still answered, and answered before it.
    assertThat(second).anySatisfy(m -> assertThat(m).isInstanceOf(ToolResponseMessage.class));
    assertThat(listener.outcomes).containsExactly(AgentOutcome.COMPLETED);

    // And it is a message of the conversation like any other, so what the turn leaves behind holds
    // it: a correction the next turn could not see would be a correction the user has to repeat.
    // Once, not once per iteration it survived into.
    final var saved = ArgumentCaptor.forClass(List.class);
    verify(chatMemoryRepository, atLeast(1)).saveAll(eq("om_root"), saved.capture());
    final var remembered =
        saved.getAllValues().stream()
            .flatMap(messages -> ((List<Message>) messages).stream())
            .filter(message -> message.getText().contains("it should be kezhenxu94/spring-agent"))
            .toList();
    assertThat(remembered).hasSize(1);
  }

  @Test
  @DisplayName("a message queued onto a run does not start a run of its own")
  void aQueuedMessageDoesNotStartARun() {
    final var queued = new RecordingListener();
    agentWithInterceptingManager(toolCallback("SearchRepository", "nothing found"));
    chatModel.callToolOnce("SearchRepository");
    queueDuringTheToolCall("it should be kezhenxu94/spring-agent", queued);

    fireAndAwait(request());

    // No second run, and so no second card and no second stop button: the message was answered by
    // the run that was already going.
    assertThat(queued.outcomes).isEmpty();
  }

  @Test
  @DisplayName("a message the run never reads is answered as a run of its own")
  void aMessageTheRunNeverReadsIsFired() throws Exception {
    final var queued = new RecordingListener();
    agentWithInterceptingManager();

    // Nothing calls a tool here, so the turn has no point at which it could read one. The message
    // arrives while the run is streaming its answer, and the run ends without having read it.
    final var listener =
        fireAndAwait(
            request()
                .listener(
                    new AgentResponseListener() {
                      /**
                       * Once, however many chunks the answer arrives in: one message, sent once.
                       */
                      private final java.util.concurrent.atomic.AtomicBoolean sent =
                          new java.util.concurrent.atomic.AtomicBoolean();

                      @Override
                      public void onContent(final String contentSoFar) {
                        if (!sent.compareAndSet(false, true)) {
                          return;
                        }
                        agent.fireOrQueue(
                            request()
                                .requestId("req-2")
                                .listener(queued)
                                .userMessage(user -> user.text("and the other repo too"))
                                .build(),
                            () -> "and the other repo too",
                            "and the other repo too");
                      }
                    }));

    assertThat(listener.outcomes).containsExactly(AgentOutcome.COMPLETED);
    // Answered after the run it was queued onto had ended, rather than dropped.
    assertThat(queued.finished.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(queued.outcomes).containsExactly(AgentOutcome.COMPLETED);
  }

  @Test
  @DisplayName("what somebody else says in the same conversation is a run of its own")
  void anotherPersonIsNotQueuedOntoThisRun() throws Exception {
    final var theirs = new RecordingListener();
    agentWithInterceptingManager(toolCallback("SearchRepository", "nothing found"));
    chatModel.callToolOnce("SearchRepository");
    // The same conversation — a card in a group chat is in front of everyone — but not the same
    // person, so it is their question rather than a correction of this one.
    queueDuringTheToolCall(
        "what about my repo", theirs, request -> request.userId("ou_2").requestId("req-2"));

    fireAndAwait(request());

    assertThat(theirs.finished.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(theirs.outcomes).containsExactly(AgentOutcome.COMPLETED);
  }

  /** Queues {@code message} onto the run from inside the tool call the run is waiting on. */
  private void queueDuringTheToolCall(final String message) {
    queueDuringTheToolCall(message, new RecordingListener());
  }

  private void queueDuringTheToolCall(final String message, final AgentResponseListener listener) {
    queueDuringTheToolCall(message, listener, request -> request.requestId("req-2"));
  }

  private void queueDuringTheToolCall(
      final String message,
      final AgentResponseListener listener,
      final Consumer<AgentRequest.AgentRequestBuilder> whoSentIt) {
    queueingInterceptor =
        () -> {
          final var builder = request().listener(listener);
          whoSentIt.accept(builder);
          agent.fireOrQueue(
              builder.userMessage(user -> user.text(message)).build(), () -> message, message);
        };
  }

  /**
   * What arrives while the tool call is being made, set by {@link #queueDuringTheToolCall}. Read
   * through the interceptor below rather than wired as one, so that a test can arrange the agent
   * first and say what arrives afterwards.
   */
  private volatile Runnable queueingInterceptor;

  /**
   * The agent as the application wires it where tool calls are concerned: over {@link
   * InterceptingToolCallingManager}, which is what reads a message queued onto a running turn.
   */
  private void agentWithInterceptingManager(final ToolCallback... tools) {
    final var whatArrivesMidCall =
        new ToolCallInterceptor() {
          @Override
          public String afterCall(
              final String toolName,
              final String toolInput,
              final String toolResult,
              final ToolContext toolContext) {
            final var arriving = queueingInterceptor;
            if (arriving != null) {
              arriving.run();
            }
            return toolResult;
          }
        };
    final var manager =
        new InterceptingToolCallingManager(
            DefaultToolCallingManager.builder().build(), List.of(whatArrivesMidCall), noFileRefs());
    final var chatMemory =
        MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
    try {
      when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
          .thenReturn(
              new AgentComposition(
                  tools,
                  autoMemoryAdvisors(),
                  new McpTools(List.of(mcpClient), new ToolCallback[0])));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    agent =
        new SpringAgent(
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            chatMemory,
            properties(),
            new Admins(properties()),
            agentToolsProvider,
            pendingQuestions,
            messagesIn(Locale.ENGLISH),
            listenerProvider(),
            contributorProvider(),
            recorderProvider(new AskedQuestionsRecorder(chatMemory, messagesIn(Locale.ENGLISH))),
            providerOf(ToolCallingAdvisor.builder().toolCallingManager(manager)));
  }

  @Test
  @DisplayName("the loop keeps its own messages when chat memory drops the tool ones")
  void theLoopCarriesItsOwnMessagesWhenMemoryIsLossy() throws Exception {
    // A repository that drops tool messages the way JdbcChatMemoryRepository does. The loop leaves
    // its working messages to chat memory unless the agent keeps the tool advisor's own history on,
    // and against a store like this that is a model which never reads back what it just did: it
    // repeats a tool instead of carrying its result into the next call.
    final var repository = new ToolMessageDroppingRepository();
    final var chatMemory =
        MessageWindowChatMemory.builder().chatMemoryRepository(repository).build();
    when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            new AgentComposition(
                new Object[] {toolCallback("CurrentDateTime", "2026-08-16T10:00:00+08:00")},
                autoMemoryAdvisors(),
                new McpTools(List.of(mcpClient), new ToolCallback[0])));
    agent =
        new SpringAgent(
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            chatMemory,
            properties(),
            new Admins(properties()),
            agentToolsProvider,
            pendingQuestions,
            messagesIn(Locale.ENGLISH),
            listenerProvider(),
            contributorProvider(),
            recorderProvider(new AskedQuestionsRecorder(chatMemory, messagesIn(Locale.ENGLISH))),
            providerOf(ToolCallingAdvisor.builder()));
    chatModel.callToolOnce("CurrentDateTime");

    fireAndAwait(request());

    assertThat(chatModel.prompts).as("the loop never came back for a second call").hasSize(2);
    final var second = chatModel.prompts.get(1).getInstructions();
    // What the tool did has to still be there, or the next call has nothing to work from.
    assertThat(second)
        .anySatisfy(
            message ->
                assertThat(message)
                    .isInstanceOfSatisfying(
                        ToolResponseMessage.class,
                        tool ->
                            assertThat(tool.getResponses())
                                .anySatisfy(
                                    response ->
                                        assertThat(response.responseData())
                                            .contains("2026-08-16T10:00:00+08:00"))));
    assertThat(second)
        .anySatisfy(
            message ->
                assertThat(message)
                    .isInstanceOfSatisfying(
                        AssistantMessage.class,
                        assistant -> assertThat(assistant.hasToolCalls()).isTrue()));
    // And carried once: memory is prepended on every iteration beside the loop's own history, so a
    // turn appearing twice is the failure this arrangement could otherwise introduce.
    assertThat(second.stream().filter(m -> "hi".equals(m.getText())).count()).isEqualTo(1);
  }

  /** Keeps everything a JPA deployment's repository would, and drops everything it would not. */
  private static final class ToolMessageDroppingRepository implements ChatMemoryRepository {
    private final Map<String, List<Message>> conversations = new LinkedHashMap<>();

    @Override
    public List<String> findConversationIds() {
      return List.copyOf(conversations.keySet());
    }

    @Override
    public List<Message> findByConversationId(final String conversationId) {
      return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void saveAll(final String conversationId, final List<Message> messages) {
      conversations.put(
          conversationId,
          messages.stream()
              .filter(
                  m ->
                      !(m instanceof ToolResponseMessage)
                          && !(m instanceof AssistantMessage a && a.hasToolCalls()))
              .toList());
    }

    @Override
    public void deleteByConversationId(final String conversationId) {
      conversations.remove(conversationId);
    }
  }

  private static ToolCallback toolCallback(final String name, final String result) {
    final var definition =
        ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return result;
      }

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return result;
      }
    };
  }

  /** A channel that puts the questions up and answers with its own name, so a merge is visible. */
  private static final class RecordingQuestionHandler implements QuestionHandler {

    private final String name;
    private final List<List<Question>> asked = new ArrayList<>();

    RecordingQuestionHandler(final String name) {
      this.name = name;
    }

    @Override
    public Map<String, String> handle(final List<Question> questions) {
      asked.add(questions);
      final var answers = new LinkedHashMap<String, String>();
      questions.forEach(question -> answers.put(question.question(), name));
      return answers;
    }
  }

  private static final class RecordingListener implements AgentResponseListener {
    private final List<String> contents = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private final List<AgentOutcome> outcomes = new ArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private boolean stopAfterFirstContent;

    @Override
    public void onContent(String contentSoFar) {
      contents.add(contentSoFar);
    }

    @Override
    public void onError(Throwable error) {
      errors.add(error);
    }

    @Override
    public void onFinished(AgentOutcome outcome) {
      outcomes.add(outcome);
      finished.countDown();
    }

    @Override
    public boolean shouldContinue() {
      return !stopAfterFirstContent || contents.isEmpty();
    }
  }

  /** Streams two chunks and remembers the tool context it was called with. */
  private static final class RecordingChatModel implements ChatModel {
    private volatile Prompt lastPrompt;
    private final List<Prompt> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String callToolOnce;
    private final java.util.concurrent.atomic.AtomicBoolean toolCalled =
        new java.util.concurrent.atomic.AtomicBoolean();
    private RuntimeException failure;
    private volatile boolean reportUsage;

    /** Makes the first response a call to {@code toolName}, and every one after it plain text. */
    void callToolOnce(final String toolName) {
      this.callToolOnce = toolName;
    }

    void failWith(final RuntimeException failure) {
      this.failure = failure;
    }

    /** Off by default: a response with no metadata is what most of these tests want. */
    void reportUsage() {
      this.reportUsage = true;
    }

    Map<String, Object> lastToolContext() {
      assertThat(lastPrompt).as("the model was never called").isNotNull();
      return ((ToolCallingChatOptions) lastPrompt.getOptions()).getToolContext();
    }

    /**
     * Real model options (OpenAI's) are {@link ToolCallingChatOptions}, and that is the only kind
     * {@code ChatClient} copies a tool context into.
     */
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
      lastPrompt = prompt;
      prompts.add(prompt);
      if (failure != null) {
        return Flux.error(failure);
      }
      if (callToolOnce != null && toolCalled.compareAndSet(false, true)) {
        return Flux.just(toolCallResponse(callToolOnce));
      }
      return Flux.just(response("hello "), response("world"));
    }

    private static ChatResponse toolCallResponse(final String toolName) {
      return new ChatResponse(
          List.of(
              new Generation(
                  AssistantMessage.builder()
                      .content("")
                      .toolCalls(
                          List.of(
                              new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                      .build())));
    }

    private ChatResponse response(final String text) {
      if (!reportUsage) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
      }
      // Named after the run it answers, so a listener can say which run the tokens came from.
      final var model =
          lastPrompt.getInstructions().stream()
                  .anyMatch(message -> SUBAGENT_MESSAGE.equals(message.getText()))
              ? "child-model"
              : "parent-model";
      return ChatResponse.builder()
          .generations(List.of(new Generation(new AssistantMessage(text))))
          .metadata(
              ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(1, 2, 3)).build())
          .build();
    }
  }

  /** No parameter takes a file reference here, so nothing an argument holds is ever expanded. */
  private static ToolInputFileRefs noFileRefs() {
    return new ToolInputFileRefs(null, null, null, List.of());
  }
}
