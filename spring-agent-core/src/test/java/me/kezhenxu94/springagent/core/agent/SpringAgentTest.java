package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentTools;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.QuestionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * Covers what {@link SpringAgent#fire} took over from its callers: assembling the run's identity,
 * and releasing the MCP clients exactly once however the run ends.
 */
class SpringAgentTest {

  @TempDir Path memoriesDirectory;

  private final McpSyncClient mcpClient = mock(McpSyncClient.class);
  private final AgentToolsProvider agentToolsProvider = mock(AgentToolsProvider.class);
  private final RecordingChatModel chatModel = new RecordingChatModel();
  private final ChatMemoryRepository chatMemoryRepository = mock(ChatMemoryRepository.class);

  /** Stands in for an integration taking part in a run it did not initiate. */
  private AgentResponseListener declaredListener = new AgentResponseListener() {};

  private SpringAgent agent;

  @BeforeEach
  void setUp() throws Exception {
    when(agentToolsProvider.compose(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new AgentComposition(
                new AgentTools(
                    null, Optional.empty(), new McpTools(List.of(mcpClient), new ToolCallback[0])),
                new Object[0],
                new ToolCallback[0],
                memoriesDirectory.toString()));
    agent =
        new SpringAgent(
            // Real model options (OpenAI's) are ToolCallingChatOptions, which is the only kind
            // that carries a tool context; the plain default ones would silently drop it.
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build(),
            properties(),
            agentToolsProvider,
            listenerProvider());
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
  @DisplayName("a run that completes reports COMPLETED and releases its MCP clients")
  void completedRun() {
    final var listener = fireAndAwait(request());

    assertThat(listener.outcomes).containsExactly(AgentOutcome.COMPLETED);
    assertThat(listener.contents).containsExactly("hello ", "hello world");
    verify(mcpClient, times(1)).close();
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
    verify(mcpClient, times(1)).close();
  }

  @Test
  @DisplayName("a run that cannot be composed reports FAILED rather than failing silently")
  void compositionFailureRun() throws Exception {
    when(agentToolsProvider.compose(any(), any(), any(), any(), any(), any()))
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
    verify(mcpClient, times(1)).close();
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
    verify(agentToolsProvider, times(0)).compose(any(), any(), any(), any(), any(), any());
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
    verify(mcpClient, times(1)).close();
  }

  @Test
  @DisplayName("putting questions to the user leaves a note the next run can read back")
  void askingIsRecordedInTheConversation() throws Exception {
    final var answers = askIn(request());

    // Whatever the integration answered with reaches the model unchanged.
    assertThat(answers).containsEntry("Which database should we use?", "not answered yet");
    assertThat(savedText())
        .anySatisfy(
            text ->
                assertThat(text)
                    .contains("already put these questions to the user")
                    .contains("Database: Which database should we use?")
                    .contains("Do not ask them again"));
  }

  @Test
  @DisplayName("a run with no conversation memory has nothing to leave the note in")
  void askingIsNotRecordedWithoutConversationMemory() throws Exception {
    askIn(request().scenario(AgentScenario.SCHEDULED_TASK));

    assertThat(savedText()).noneSatisfy(text -> assertThat(text).contains("already put these"));
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
    verify(agentToolsProvider).compose(any(), any(), any(), any(), any(), composed.capture());
    return composed
        .getValue()
        .handle(
            List.of(new Question("Which database should we use?", "Database", List.of(), false)));
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
  @DisplayName("a request arriving during shutdown is dropped without composing anything")
  void droppedDuringShutdown() throws Exception {
    final var listener = new RecordingListener();
    agent.onShutdown();

    agent.fire(request().listener(listener).build());

    assertThat(listener.outcomes).isEmpty();
    verify(agentToolsProvider, times(0)).compose(any(), any(), any(), any(), any(), any());
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

  private static AgentRequest.AgentRequestBuilder request() {
    return AgentRequest.builder()
        .requestId("req-1")
        .scenario(AgentScenario.CHAT)
        .userId("ou_1")
        .chatId("oc_1")
        .chatType("group")
        .conversationId("om_root")
        .rootMessageId("om_root")
        .replyMessageId("om_reply")
        .userMessage(user -> user.text("hi"));
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null,
        new SpringAgentProperties.Ai(
            null,
            Set.of(),
            Map.of(),
            null,
            null,
            "You are {userId} in {chatId} ({chatType}), thread {threadId}, parent {parentId},"
                + " mentions {mentions}.",
            null));
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
    private RuntimeException failure;

    void failWith(final RuntimeException failure) {
      this.failure = failure;
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
      if (failure != null) {
        return Flux.error(failure);
      }
      return Flux.just(response("hello "), response("world"));
    }

    private static ChatResponse response(final String text) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
  }
}
