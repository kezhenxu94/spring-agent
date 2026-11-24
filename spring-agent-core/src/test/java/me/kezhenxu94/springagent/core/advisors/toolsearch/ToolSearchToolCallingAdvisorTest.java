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

  private static final String CATALOGUE = "- Alpha, Zeta" + System.lineSeparator();

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
        .doesNotContain("Zeta");
  }

  @Test
  @DisplayName("a run's own always-loaded tools join the fixed array for that run only")
  void runsOwnAlwaysLoadedTools() {
    final var advisor = advisor();
    final var request =
        turnWith(List.of(new SystemMessage("x")))
            .mutate()
            .context(ToolSearchToolCallingAdvisor.RUN_ALWAYS_LOADED_KEY, java.util.Set.of("Alpha"))
            .build();

    final var own = advisor.doBeforeCall(advisor.doInitializeLoop(request, chain), chain);
    final var plain =
        advisor.doBeforeCall(
            advisor.doInitializeLoop(turnWith(List.of(new SystemMessage("x"))), chain), chain);

    assertThat(offeredNames(own))
        .containsExactly("Alpha", "CallTool", "Grep", "Read", "toolSearchTool");
    assertThat(offeredNames(plain)).containsExactly("CallTool", "Grep", "Read", "toolSearchTool");
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

  private static ToolDefinition definition(final String name, final String description) {
    return ToolDefinition.builder()
        .name(name)
        .description(description)
        .inputSchema("{\"type\":\"object\"}")
        .build();
  }

  @Test
  @DisplayName(
      "the catalogue lists names only, a group to a line: its name, its purpose, its tools after"
          + " their shared prefix")
  void catalogueShape() {
    final var git = new ToolGroupsAdvisor.ToolGroup("git", "git_", "Repositories and issues");
    final var app = new ToolGroupsAdvisor.ToolGroup("weather", "", "");
    final var skills = new ToolGroupsAdvisor.ToolGroup("skills", "skill_", "your skills");
    final var catalogue =
        ToolSearchToolCallingAdvisor.catalogue(
            List.of(
                definition("git_search", "Searches.\nMore."),
                definition("Remind", "Sets a reminder."),
                definition("skill_triage", "Triage."),
                definition("forecast", "Forecasts."),
                definition("git_close", "Closes."),
                definition("alerts", "Alerts.")),
            Map.of(
                "git_search", git,
                "git_close", git,
                "forecast", app,
                "alerts", app,
                "skill_triage", skills));

    assertThat(catalogue.lines().toList())
        .containsExactly(
            "- git — Repositories and issues: git_{close, search}",
            "- skills — your skills: skill_{triage}",
            "- weather: alerts, forecast",
            "- Remind");
  }

  /**
   * A few hundred tools, as a deployment with several MCP servers and skills reaches: eight servers
   * of fifty tools, thirty skills, ten tools of no group, each described at the length real ones
   * are.
   */
  private static List<ToolDefinition> manyTools() {
    final var definitions = new ArrayList<ToolDefinition>();
    final var description =
        "Does one particular thing to one particular kind of record, with enough words to read"
            + " like a real description of a real tool that somebody wrote.\nAnd more.";
    for (var server = 0; server < 8; server++) {
      for (var tool = 0; tool < 50; tool++) {
        definitions.add(definition("srv" + server + "_get_record_kind_" + tool, description));
      }
    }
    for (var skill = 0; skill < 30; skill++) {
      definitions.add(definition("skill_some-saved-method-" + skill, description));
    }
    for (var tool = 0; tool < 10; tool++) {
      definitions.add(definition("ManageSomething" + tool, description));
    }
    return definitions;
  }

  private static Map<String, ToolGroupsAdvisor.ToolGroup> groupsOf(
      final List<ToolDefinition> definitions) {
    final var groups = new java.util.HashMap<String, ToolGroupsAdvisor.ToolGroup>();
    for (final var definition : definitions) {
      final var name = definition.name();
      if (name.startsWith("skill_")) {
        groups.put(name, new ToolGroupsAdvisor.ToolGroup("skills", "skill_", "your skills"));
      } else if (name.startsWith("srv")) {
        final var prefix = name.substring(0, name.indexOf('_') + 1);
        groups.put(
            name,
            new ToolGroupsAdvisor.ToolGroup(
                prefix.substring(0, prefix.length() - 1), prefix, "records of one system"));
      }
    }
    return groups;
  }

  @Test
  @DisplayName("four hundred and forty tools make a catalogue of a few thousand tokens at most")
  void catalogueSizeBound() {
    final var tools = manyTools();
    final var catalogue = ToolSearchToolCallingAdvisor.catalogue(tools, groupsOf(tools));
    // What the one-line-per-tool catalogue it replaced came to for the same tools.
    final var before =
        tools.stream()
            .mapToInt(
                d ->
                    ("- " + d.name() + ": " + d.description().lines().findFirst().orElse("") + "\n")
                        .length())
            .sum();
    System.out.printf(
        "CATALOGUE %d tools: before %d chars, after %d chars%n",
        tools.size(), before, catalogue.length());

    assertThat(tools).hasSize(440);
    // About four characters a token for identifiers, so under four thousand tokens.
    assertThat(catalogue.length()).isLessThan(12_000);
  }

  @Test
  @DisplayName("the same tools in any order make the same catalogue, byte for byte")
  void catalogueIsDeterministic() {
    final var tools = manyTools();
    final var shuffled = new ArrayList<>(tools);
    java.util.Collections.shuffle(shuffled, new java.util.Random(7));

    assertThat(ToolSearchToolCallingAdvisor.catalogue(shuffled, groupsOf(shuffled)))
        .isEqualTo(ToolSearchToolCallingAdvisor.catalogue(tools, groupsOf(tools)));
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
