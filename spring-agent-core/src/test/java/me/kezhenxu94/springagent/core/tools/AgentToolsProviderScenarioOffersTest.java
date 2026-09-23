package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.config.TenantWrites;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link AgentScenario#offers} rules on everything a run is composed of, not only on the
 * {@code @AgentTool} beans.
 *
 * <p>This is the axis that makes an allow-list mean what it says. While {@code offers} was asked in
 * one place only, a scenario naming the two tools it wanted was also handed a file-system sandbox,
 * the todo tool, the ask and every application-wide MCP server — silently, since nothing in the
 * composition said so and the model simply found them there.
 */
class AgentToolsProviderScenarioOffersTest {

  @TempDir Path workspace;

  @Configuration
  static class GlobalTools {
    @Bean
    ToolCallbackProvider globalProvider() {
      return () -> new ToolCallback[] {stubCallback("global_search")};
    }
  }

  /** Nothing at all, which is the strongest reading of an allow-list that names nothing. */
  private static final AgentScenario NOTHING =
      new AgentScenario() {
        @Override
        public boolean offers(final Object tool) {
          return false;
        }
      };

  /** Everything but the callbacks, ruled on by name through the overload. */
  private static final AgentScenario NO_GLOBAL_SEARCH =
      new AgentScenario() {
        @Override
        public boolean offers(final ToolCallback tool) {
          return !"global_search".equals(tool.getToolDefinition().name());
        }
      };

  @Test
  @DisplayName("a chat run is offered the sandbox, the todo tool, the ask and the global servers")
  void aChatRunGetsEverything() throws Exception {
    final var tools = compose(BuiltInScenarios.CHAT);
    assertThat(tools).hasAtLeastOneElementOfType(FileSystemTools.class);
    assertThat(tools).hasAtLeastOneElementOfType(TodoWriteTool.class);
    // Wrapped into a callback on this path, since the answer arrives later — see endsTurnCallback.
    assertThat(callbackNames(tools)).contains("AskUserQuestionTool", "global_search");
  }

  @Test
  @DisplayName("a scenario that offers nothing is composed with nothing, callbacks included")
  void anAllowListThatNamesNothingGetsNothing() throws Exception {
    // Every one of these reached a run whatever the scenario said, because each is added by the
    // composition rather than discovered as an @AgentTool bean.
    final var tools = compose(NOTHING);
    assertThat(tools).doesNotHaveAnyElementsOfTypes(FileSystemTools.class);
    assertThat(tools).doesNotHaveAnyElementsOfTypes(TodoWriteTool.class);
    assertThat(callbackNames(tools)).isEmpty();
    assertThat(tools).isEmpty();
  }

  @Test
  @DisplayName("a callback is ruled on by name, which is the only thing telling them apart")
  void theOverloadRulesOnCallbacksByName() throws Exception {
    // An MCP server's tools and a skill's are all ToolCallback whatever they came from, so there
    // is no type to write an instanceof against — the name is it.
    final var tools = compose(NO_GLOBAL_SEARCH);
    assertThat(callbackNames(tools)).doesNotContain("global_search");
    // And nothing else went with it: the overload delegates, so the plain tools are unaffected.
    assertThat(tools).hasAtLeastOneElementOfType(FileSystemTools.class);
  }

  private Object[] compose(final AgentScenario scenario) throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forRequest(eq("ou_1"), nullable(String.class), nullable(String.class)))
        .thenReturn(new UserHome(workspace));
    when(workspaces.forOwner(eq("ou_1"))).thenReturn(new UserHome(workspace));
    try (var context = new AnnotationConfigApplicationContext(GlobalTools.class)) {
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties(),
              new Admins(properties()),
              new TenantWrites(properties(), new Admins(properties())),
              TestI18n.english(),
              mock(ObjectProvider.class));
      return provider
          .compose(
              AgentRequest.builder()
                  .scenario(scenario)
                  .userId("ou_1")
                  .chatId("oc_1")
                  .userMessage(user -> user.text("What do we know about this?"))
                  .build(),
              Map.of(),
              todos -> {},
              questions -> Map.of(),
              true,
              references -> {})
          .tools();
    }
  }

  private static java.util.List<String> callbackNames(final Object[] tools) {
    return java.util.Arrays.stream(tools)
        .filter(ToolCallback.class::isInstance)
        .map(tool -> ((ToolCallback) tool).getToolDefinition().name())
        .toList();
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

      @Override
      public String call(final String toolInput, final ToolContext toolContext) {
        return "";
      }
    };
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
