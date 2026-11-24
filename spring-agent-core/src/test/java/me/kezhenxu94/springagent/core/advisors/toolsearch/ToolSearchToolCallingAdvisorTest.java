package me.kezhenxu94.springagent.core.advisors.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

/**
 * What this fork changed from upstream: the {@code tools} array is fixed for a whole run and
 * sorted, everything else is listed in the system prompt and reached through {@code toolSearchTool}
 * — which answers with schemas — and {@code CallTool}; and the suffix lands on the last system
 * message.
 */
class ToolSearchToolCallingAdvisorTest {

  private final CallAdvisorChain chain = mock(CallAdvisorChain.class);

  private ToolSearchToolCallingAdvisor advisor() {
    return ToolSearchToolCallingAdvisor.builder()
        .toolCallingManager(ToolCallingManager.builder().build())
        .toolIndex(new StubToolIndex())
        .systemMessageSuffix("\n\nSEARCH FOR TOOLS")
        .sessionIdKeyName(ChatMemory.CONVERSATION_ID)
        .dispatcher(targets -> tool("CallTool", "Dispatch " + targets.keySet()))
        .build();
  }

  private static ToolCallback tool(final String name, final String description) {
    final var definition =
        ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "\"" + name + "\"";
      }
    };
  }

  /** Deliberately out of order, as a composition arrives. */
  private static final List<ToolCallback> TOOLS =
      List.of(
          tool("Zeta", "Does zeta things.\nSecond line, never listed."),
          tool("Read", "Reads a file."),
          tool("Alpha", "Does alpha things."),
          tool("Grep", "Searches files."));

  private static ChatClientRequest turnWith(
      final List<Message> systemMessages, final List<ToolCallback> tools) {
    final var messages = new ArrayList<Message>(systemMessages);
    messages.add(new UserMessage("what tools do you have"));
    return ChatClientRequest.builder()
        .prompt(new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(tools).build()))
        .context(Map.of(ChatMemory.CONVERSATION_ID, "conv-1"))
        .build();
  }

  private static ChatClientRequest turnWith(final List<Message> systemMessages) {
    return turnWith(systemMessages, TOOLS);
  }

  private static List<SystemMessage> systemMessagesOf(final ChatClientRequest request) {
    return request.prompt().getInstructions().stream()
        .filter(SystemMessage.class::isInstance)
        .map(SystemMessage.class::cast)
        .toList();
  }

  private static List<String> offeredNames(final ChatClientRequest request) {
    return ((ToolCallingChatOptions) request.prompt().getOptions())
        .getToolCallbacks().stream().map(tool -> tool.getToolDefinition().name()).toList();
  }

  private static final String CATALOGUE =
      "- Alpha: Does alpha things.\n- Zeta: Does zeta things.\n";

  @Test
  @DisplayName(
      "every iteration is offered the same tools, sorted: the always-loaded ones, the search and"
          + " CallTool")
  void fixedSortedTools() {
    final var advisor = advisor();
    final var initialized =
        advisor.doInitializeLoop(turnWith(List.of(new SystemMessage("You are an agent."))), chain);

    final var first = advisor.doBeforeCall(initialized, chain);
    final var second = advisor.doBeforeCall(first, chain);

    assertThat(offeredNames(first)).containsExactly("CallTool", "Grep", "Read", "toolSearchTool");
    assertThat(offeredNames(second)).isEqualTo(offeredNames(first));
  }

  @Test
  @DisplayName("a tool the application names is offered directly too, in the same sorted array")
  void applicationNamedTools() {
    final var advisor =
        ToolSearchToolCallingAdvisor.builder()
            .toolCallingManager(ToolCallingManager.builder().build())
            .toolIndex(new StubToolIndex())
            .systemMessageSuffix("\n\nSEARCH FOR TOOLS")
            .sessionIdKeyName(ChatMemory.CONVERSATION_ID)
            .dispatcher(targets -> tool("CallTool", "Dispatch " + targets.keySet()))
            .alwaysLoaded(List.of("Zeta", " ", "NotCarried"))
            .build();

    final var request =
        advisor.doBeforeCall(
            advisor.doInitializeLoop(turnWith(List.of(new SystemMessage("x"))), chain), chain);

    assertThat(offeredNames(request))
        .containsExactly("CallTool", "Grep", "Read", "Zeta", "toolSearchTool");
    assertThat(systemMessagesOf(request).get(0).getText())
        .contains("- Alpha")
        .doesNotContain("- Zeta");
  }

  @Test
  @DisplayName("CallTool can reach exactly the tools that are not offered directly")
  void dispatcherTargets() {
    final var advisor = advisor();
    final var request =
        advisor.doBeforeCall(
            advisor.doInitializeLoop(turnWith(List.of(new SystemMessage("x"))), chain), chain);
    final var callTool =
        ((ToolCallingChatOptions) request.prompt().getOptions())
            .getToolCallbacks().stream()
                .filter(tool -> tool.getToolDefinition().name().equals("CallTool"))
                .findFirst()
                .orElseThrow();
    assertThat(callTool.getToolDefinition().description()).isEqualTo("Dispatch [Alpha, Zeta]");
  }

  @Test
  @DisplayName("the search answers with each match's schema, not only its name")
  void searchAnswersWithSchemas() {
    final var advisor = advisor();
    final var request =
        advisor.doBeforeCall(
            advisor.doInitializeLoop(turnWith(List.of(new SystemMessage("x"))), chain), chain);
    final var options = (ToolCallingChatOptions) request.prompt().getOptions();
    final var search =
        options.getToolCallbacks().stream()
            .filter(tool -> tool.getToolDefinition().name().equals("toolSearchTool"))
            .findFirst()
            .orElseThrow();

    final var result =
        search.call(
            "{\"query\":\"zeta\"}",
            new org.springframework.ai.chat.model.ToolContext(options.getToolContext()));

    assertThat(result)
        .contains("\"name\":\"Zeta\"")
        .contains("\"input_schema\":{\"type\":\"object\"")
        .doesNotContain("Unknown");
  }

  @Test
  @DisplayName("with one system message, the suffix and the catalogue land on it")
  void oneSystemMessage() {
    final var result =
        advisor()
            .doInitializeLoop(turnWith(List.of(new SystemMessage("You are an agent."))), chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(1);
    assertThat(systemMessages.get(0).getText())
        .isEqualTo("You are an agent.\n\nSEARCH FOR TOOLS" + System.lineSeparator() + CATALOGUE);
  }

  @Test
  @DisplayName(
      "with several system messages — one per configured system-prompt part — the suffix lands"
          + " on the last one, not the first")
  void appendsToTheLastOfSeveral() {
    final var result =
        advisor()
            .doInitializeLoop(
                turnWith(
                    List.of(
                        new SystemMessage("Static house rules."),
                        new SystemMessage("You are an agent."))),
                chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(2);
    assertThat(systemMessages.get(0).getText()).isEqualTo("Static house rules.");
    assertThat(systemMessages.get(1).getText()).startsWith("You are an agent.\n\nSEARCH FOR TOOLS");
  }

  @Test
  @DisplayName("a request with no system message gets one rather than an NPE")
  void noSystemMessage() {
    final var result = advisor().doInitializeLoop(turnWith(List.of()), chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(1);
    assertThat(systemMessages.get(0).getText()).startsWith("\n\nSEARCH FOR TOOLS");
  }

  @Test
  @DisplayName("a run whose tools are all always-loaded gets no search, no CallTool and no suffix")
  void nothingDeferred() {
    final var advisor = advisor();
    final var request =
        advisor.doBeforeCall(
            advisor.doInitializeLoop(
                turnWith(
                    List.of(new SystemMessage("You are an agent.")),
                    List.of(tool("Read", "Reads a file."))),
                chain),
            chain);

    assertThat(offeredNames(request)).containsExactly("Read");
    assertThat(systemMessagesOf(request).get(0).getText()).isEqualTo("You are an agent.");
  }

  /** Finds Zeta, and a tool this run does not carry, which the search must leave out. */
  private static final class StubToolIndex implements ToolIndex {
    @Override
    public void indexTool(final String sessionId, final ToolReference toolReference) {}

    @Override
    public ToolSearchResponse search(final ToolSearchRequest toolSearchRequest) {
      return ToolSearchResponse.builder()
          .toolReferences(
              List.of(
                  ToolReference.builder().toolName("Zeta").summary("z").build(),
                  ToolReference.builder().toolName("Unknown").summary("u").build()))
          .build();
    }

    @Override
    public void clearIndex(final String sessionId) {}
  }
}
