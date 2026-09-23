package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.AgentComposition;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider.McpTools;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * {@link AgentScenario#toolSearch()} decides whether a run's tools reach the model through the tool
 * search or directly.
 *
 * <p>Worth its own class because the tool-search advisor has no per-request switch: it indexes and
 * rewrites the options on everything it sees. Declining it is therefore a matter of the run being
 * handed a different advisor, and what these tests check is that the handing-over actually happens
 * — which is invisible from anywhere but the prompt the model was given.
 */
class ScenarioToolSearchTest {

  private final RecordingChatModel chatModel = new RecordingChatModel();
  private final AgentToolsProvider agentToolsProvider = mock(AgentToolsProvider.class);
  private final RecordingToolIndex toolIndex = new RecordingToolIndex();

  /** A scenario that declines the search; otherwise an ordinary chat run. */
  private static final AgentScenario NO_SEARCH =
      new AgentScenario() {
        @Override
        public boolean toolSearch() {
          return false;
        }
      };

  @Test
  @DisplayName("a run that wants the tool search is given one tool and told to go looking")
  void aSearchingRunSeesOnlyTheSearchTool() throws Exception {
    fire(BuiltInScenarios.CHAT);

    // What the tool search does is replace the tools, not index them as well: the model is handed
    // toolSearchTool and nothing else until it searches.
    assertThat(offeredToolNames()).containsExactly("toolSearchTool");
    assertThat(systemText()).contains("SEARCH FOR TOOLS");
    assertThat(toolIndex.indexed).isTrue();
  }

  @Test
  @DisplayName("a run that declines it is given its own tools, and no paragraph about searching")
  void aDecliningRunSeesItsOwnTools() throws Exception {
    fire(NO_SEARCH);

    assertThat(offeredToolNames()).containsExactly("SearchKnowledge");
    assertThat(systemText()).doesNotContain("SEARCH FOR TOOLS");
    // Nothing was embedded either, which is the other half of what declining buys.
    assertThat(toolIndex.indexed).isFalse();
  }

  @Test
  @DisplayName("the built-ins that know their tool set is small decline it")
  void theNarrowScenariosDeclineIt() {
    // KNOWLEDGE_BASE composes four tools on the best day, so a search would spend a round trip
    // discovering what fits in the prompt. ONE_OFF composes none at all, and was reaching the
    // model with the search tool and an explanation of how to use it.
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.toolSearch()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.toolSearch()).isFalse();
    // Everything else is open-ended, which is the case the tool search exists for.
    assertThat(BuiltInScenarios.CHAT.toolSearch()).isTrue();
    assertThat(BuiltInScenarios.SUBAGENT.toolSearch()).isTrue();
    assertThat(BuiltInScenarios.SCHEDULED_TASK.toolSearch()).isTrue();
  }

  private void fire(final AgentScenario scenario) throws Exception {
    final var tool = stubCallback("SearchKnowledge");
    when(agentToolsProvider.compose(any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(
            new AgentComposition(
                new Object[] {tool}, List.of(), new McpTools(List.of(), new ToolCallback[0])));
    final var chatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(mock(ChatMemoryRepository.class))
            .build();
    final var manager = ToolCallingManager.builder().build();
    final var agent =
        new SpringAgent(
            ChatClient.builder(chatModel).defaultOptions(ToolCallingChatOptions.builder()).build(),
            providerOf((UserChatClients) null),
            chatMemory,
            properties(),
            new Admins(properties()),
            agentToolsProvider,
            mock(PendingQuestionRepo.class),
            messages(),
            providerOf((AgentResponseListener) null),
            providerOf((PromptVariablesContributor) null),
            providerOf((AskedQuestionsRecorder) null),
            // The searching builder, exactly as ToolSearchAdvisorAutoConfiguration registers it.
            providerOf(
                ToolSearchToolCallingAdvisor.builder()
                    .toolIndex(toolIndex)
                    .systemMessageSuffix("\n\nSEARCH FOR TOOLS")
                    .sessionIdKeyName(SpringAgent.TOOL_INDEX_KEY)
                    .toolCallingManager(manager)),
            manager,
            providerOf((ToolExecutionEligibilityChecker) null),
            List.<ProviderRejection>of());

    final var done = new CountDownLatch(1);
    agent.fire(
        AgentRequest.builder()
            .requestId("req-1")
            .scenario(scenario)
            .userId("ou_1")
            .chatId("oc_1")
            .conversationId("om_root")
            .userMessage(user -> user.text("what do we know about this"))
            .listener(
                new AgentResponseListener() {
                  @Override
                  public void onFinished(final AgentOutcome outcome) {
                    done.countDown();
                  }
                })
            .build());
    assertThat(done.await(10, TimeUnit.SECONDS)).as("the run did not finish in time").isTrue();
  }

  private List<String> offeredToolNames() {
    final var prompt = chatModel.lastPrompt.get();
    assertThat(prompt).as("the model was never called").isNotNull();
    return ((ToolCallingChatOptions) prompt.getOptions())
        .getToolCallbacks().stream().map(c -> c.getToolDefinition().name()).sorted().toList();
  }

  private String systemText() {
    return chatModel.lastPrompt.get().getInstructions().stream()
        .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM)
        .map(org.springframework.ai.chat.messages.Message::getText)
        .findFirst()
        .orElse("");
  }

  /** Records whether anything was ever embedded, which is the cost declining the search avoids. */
  private static final class RecordingToolIndex implements ToolIndex {
    private volatile boolean indexed;

    @Override
    public void indexTool(final String indexKey, final ToolReference tool) {
      indexed = true;
    }

    @Override
    public ToolSearchResponse search(final ToolSearchRequest request) {
      return ToolSearchResponse.builder().toolReferences(List.of()).build();
    }

    @Override
    public void clearIndex(final String indexKey) {}
  }

  private static final class RecordingChatModel implements ChatModel {
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

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
      lastPrompt.set(prompt);
      return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
    }
  }

  private static ToolCallback stubCallback(final String name) {
    final var definition =
        ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "";
      }
    };
  }

  private static <T> ObjectProvider<T> providerOf(final T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public void ifAvailable(final Consumer<T> action) {
        if (value != null) {
          action.accept(value);
        }
      }

      @Override
      public java.util.stream.Stream<T> stream() {
        return value == null ? java.util.stream.Stream.of() : java.util.stream.Stream.of(value);
      }

      @Override
      public java.util.stream.Stream<T> orderedStream() {
        return stream();
      }
    };
  }

  private static CoreMessages messages() {
    return TestI18n.english();
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        new Ai(
            Set.of(),
            null,
            Map.of(),
            null,
            null,
            new Tools(new AskUserQuestion(true, null), null, null, null, null),
            "You are an agent.",
            null,
            null,
            null),
        Locale.ENGLISH,
        null,
        null);
  }
}
