package me.kezhenxu94.springagent.core.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

/**
 * What a turn sends the model and leaves in memory, over a store that keeps tool messages but not
 * message metadata — MongoChatMemoryRepo's shape — with a model whose replies carry metadata, as
 * OpenAI's do. That pairing is what had the turn's tool call reach OpenAI twice, the first copy
 * with no result after it.
 */
class MemoryToolCallingAdvisorTest {

  private final InMemoryChatMemoryRepository stored = new InMemoryChatMemoryRepository();

  private final ChatMemory memory =
      MessageWindowChatMemory.builder().chatMemoryRepository(new MetadataDropping(stored)).build();

  private final RecordingModel model = new RecordingModel();

  @Test
  @DisplayName("a turn that calls a tool sends the call once, answered, and keeps the whole turn")
  void keepsAToolCallingTurn() {
    model.calls("look");

    stream(advisor(ToolCallingManager.builder().build()), "hi");

    assertEveryCallIsAnswered();
    assertThat(model.prompts.getLast())
        .containsExactly("SYSTEM", "USER:hi", "ASSISTANT:look", "TOOL:look");
    assertThat(remembered()).containsExactly("USER:hi", "ASSISTANT:look", "TOOL:look", "ASSISTANT");
  }

  @Test
  @DisplayName("a later turn reads the earlier one once, ahead of its own question")
  void readsMemoryOnce() {
    final var advisor = advisor(ToolCallingManager.builder().build());
    model.calls("look");
    stream(advisor, "hi");
    model.calls("look");

    stream(advisor, "and now?");

    assertEveryCallIsAnswered();
    assertThat(model.prompts.getLast())
        .containsExactly(
            "SYSTEM",
            "USER:hi",
            "ASSISTANT:look",
            "TOOL:look",
            "ASSISTANT",
            "USER:and now?",
            "ASSISTANT:look",
            "TOOL:look");
    assertThat(remembered())
        .containsExactly(
            "USER:hi",
            "ASSISTANT:look",
            "TOOL:look",
            "ASSISTANT",
            "USER:and now?",
            "ASSISTANT:look",
            "TOOL:look",
            "ASSISTANT");
  }

  @Test
  @DisplayName("a message sent mid-run stays after the tool results, in the request and in memory")
  void keepsAMessageSentMidRunInPlace() {
    model.calls("look");

    stream(advisor(queuing("while it worked")), "hi");

    assertEveryCallIsAnswered();
    assertThat(model.prompts.getLast())
        .containsExactly(
            "SYSTEM", "USER:hi", "ASSISTANT:look", "TOOL:look", "USER:while it worked");
    assertThat(remembered())
        .containsExactly(
            "USER:hi", "ASSISTANT:look", "TOOL:look", "USER:while it worked", "ASSISTANT");
  }

  @Test
  @DisplayName("a tool that ends the turn leaves its result in memory, for the answer to follow")
  void keepsTheResultOfAToolThatEndedTheTurn() {
    model.calls("ask");

    stream(advisor(ToolCallingManager.builder().build()), "hi");

    assertThat(remembered()).containsExactly("USER:hi", "ASSISTANT:ask", "TOOL:ask");
  }

  @Test
  @DisplayName("and so does one on the call path")
  void keepsTheResultOfAToolThatEndedACalledTurn() {
    model.calls("ask");

    chatClient(advisor(ToolCallingManager.builder().build()), "hi").call().content();

    assertThat(remembered()).containsExactly("USER:hi", "ASSISTANT:ask", "TOOL:ask");
  }

  @Test
  @DisplayName("a turn that fails keeps what it finished, and never a call without its result")
  void keepsAFailedTurnValid() {
    model.calls("look");
    model.failAfterTools();

    assertThatThrownBy(() -> stream(advisor(ToolCallingManager.builder().build()), "hi"))
        .isInstanceOf(RuntimeException.class);

    assertThat(remembered()).containsExactly("USER:hi", "ASSISTANT:look", "TOOL:look");
  }

  @Test
  @DisplayName("every system message reaches every round, with memory after them")
  void keepsEverySystemMessage() {
    model.calls("look");
    stream(advisor(ToolCallingManager.builder().build()), "hi");
    model.calls("look");

    ChatClient.builder(model)
        .build()
        .prompt()
        .messages(new SystemMessage("one"), new SystemMessage("two"))
        .user("again")
        .toolCallbacks(tool("look", false), tool("ask", true))
        .advisors(conversation())
        .advisors(advisor(ToolCallingManager.builder().build()))
        .stream()
        .content()
        .blockLast();

    assertThat(model.prompts.getLast().subList(0, 3))
        .containsExactly("SYSTEM", "SYSTEM", "USER:hi");
  }

  @Test
  @DisplayName("a run that names no conversation keeps nothing")
  void keepsNothingWithoutAConversation() {
    model.calls("look");

    ChatClient.builder(model)
        .build()
        .prompt()
        .system("sys")
        .user("hi")
        .toolCallbacks(tool("look", false), tool("ask", true))
        .advisors(advisor(ToolCallingManager.builder().build()))
        .stream()
        .content()
        .blockLast();

    assertThat(stored.findConversationIds()).isEmpty();
  }

  private void stream(final MemoryToolCallingAdvisor advisor, final String question) {
    chatClient(advisor, question).stream().content().blockLast();
  }

  private ChatClient.ChatClientRequestSpec chatClient(
      final MemoryToolCallingAdvisor advisor, final String question) {
    return ChatClient.builder(model)
        .build()
        .prompt()
        .system("sys")
        .user(question)
        .toolCallbacks(tool("look", false), tool("ask", true))
        .advisors(conversation())
        .advisors(advisor);
  }

  private java.util.function.Consumer<ChatClient.AdvisorSpec> conversation() {
    return a ->
        a.param(
            MemoryToolCallingAdvisor.CONVERSATION,
            new MemoryToolCallingAdvisor.Conversation(memory, "c"));
  }

  private static MemoryToolCallingAdvisor advisor(final ToolCallingManager manager) {
    return MemoryToolCallingAdvisor.builder().toolCallingManager(manager).build();
  }

  private void assertEveryCallIsAnswered() {
    for (final var prompt : model.prompts) {
      for (var i = 0; i < prompt.size(); i++) {
        if (prompt.get(i).startsWith("ASSISTANT:")) {
          assertThat(i + 1).as("a call ends %s", prompt).isLessThan(prompt.size());
          assertThat(prompt.get(i + 1))
              .as("a call not followed by its result in %s", prompt)
              .startsWith("TOOL:");
        }
      }
    }
  }

  private List<String> remembered() {
    return stored.findByConversationId("c").stream()
        .map(MemoryToolCallingAdvisorTest::kind)
        .toList();
  }

  private static String kind(final Message message) {
    if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
      return "ASSISTANT:" + assistant.getToolCalls().getFirst().name();
    }
    if (message instanceof ToolResponseMessage tool) {
      return "TOOL:" + tool.getResponses().getFirst().name();
    }
    if (message instanceof UserMessage user) {
      return "USER:" + user.getText();
    }
    return message.getMessageType().name();
  }

  /** What InterceptingToolCallingManager does with a message the user sent while the tool ran. */
  private static ToolCallingManager queuing(final String arrived) {
    final var plain = ToolCallingManager.builder().build();
    return new ToolCallingManager() {
      @Override
      public List<ToolDefinition> resolveToolDefinitions(final ToolCallingChatOptions options) {
        return plain.resolveToolDefinitions(options);
      }

      @Override
      public ToolExecutionResult executeToolCalls(
          final Prompt prompt, final ChatResponse response) {
        final var result = plain.executeToolCalls(prompt, response);
        final var history = new ArrayList<>(result.conversationHistory());
        history.add(new UserMessage(arrived));
        return ToolExecutionResult.builder().conversationHistory(history).build();
      }
    };
  }

  /** Keeps tool messages and drops metadata, as MongoChatMemoryRepo does. */
  private record MetadataDropping(ChatMemoryRepository delegate) implements ChatMemoryRepository {

    @Override
    public List<String> findConversationIds() {
      return delegate.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(final String conversationId) {
      return delegate.findByConversationId(conversationId).stream()
          .map(
              message ->
                  message instanceof AssistantMessage assistant
                      ? (Message)
                          AssistantMessage.builder()
                              .content(assistant.getText())
                              .toolCalls(assistant.getToolCalls())
                              .build()
                      : message)
          .toList();
    }

    @Override
    public void saveAll(final String conversationId, final List<Message> messages) {
      delegate.saveAll(conversationId, messages);
    }

    @Override
    public void deleteByConversationId(final String conversationId) {
      delegate.deleteByConversationId(conversationId);
    }
  }

  /** Calls the tool it is told to on a turn's first round and answers on the next. */
  private static final class RecordingModel implements ChatModel {

    private final List<List<String>> prompts = new CopyOnWriteArrayList<>();
    private final AtomicInteger round = new AtomicInteger();
    private volatile String tool;
    private volatile boolean failAfterTools;

    void calls(final String tool) {
      this.tool = tool;
      this.round.set(0);
    }

    void failAfterTools() {
      this.failAfterTools = true;
    }

    @Override
    public ChatOptions getOptions() {
      return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(final Prompt prompt) {
      prompts.add(
          prompt.getInstructions().stream().map(MemoryToolCallingAdvisorTest::kind).toList());
      final var first = round.getAndIncrement() == 0;
      if (!first && failAfterTools) {
        throw new IllegalStateException("model is down");
      }
      // What OpenAI puts on every reply, and MongoDB does not keep.
      final var builder =
          AssistantMessage.builder().properties(Map.of("id", "chatcmpl-1", "index", 0L));
      return new ChatResponse(
          List.of(
              new Generation(
                  first
                      ? builder
                          .content("")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "call_" + prompts.size(), "function", tool, "{}")))
                          .build()
                      : builder.content("done").build())));
    }

    @Override
    public Flux<ChatResponse> stream(final Prompt prompt) {
      return Flux.defer(() -> Flux.just(call(prompt)));
    }
  }

  /**
   * A tool by name, answering with its own name; {@code endsTurn} is the ask's {@code
   * returnDirect}. Built as a callback rather than declared with {@code @Tool}, which would add it
   * to the tools CoreEveryToolTranslatedTest holds core to.
   */
  private static ToolCallback tool(final String name, final boolean endsTurn) {
    final var definition =
        ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
    final var metadata = ToolMetadata.builder().returnDirect(endsTurn).build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public ToolMetadata getToolMetadata() {
        return metadata;
      }

      @Override
      public String call(final String toolInput) {
        return name;
      }
    };
  }
}
