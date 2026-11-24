package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.advisors.toolsearch.ToolSearchToolCallingAdvisor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBaseTools;
import me.kezhenxu94.springagent.core.memory.MemoryTools;
import me.kezhenxu94.springagent.core.tools.DateTimeTool;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The size of what the always-loaded tools put on every request: each one's name, description and
 * schema, translated and with the display description added, as a request carries them.
 */
class AlwaysLoadedToolSizeTest {

  static List<ToolDefinition> alwaysLoaded(final Locale locale) {
    final var raw = new ArrayList<ToolDefinition>();
    for (final var type :
        List.<Class<?>>of(
            DateTimeTool.class, MemoryTools.class, SubagentTools.class, KnowledgeBaseTools.class)) {
      for (final var method : type.getDeclaredMethods()) {
        if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
          final var definition = org.springframework.ai.tool.support.ToolDefinitions.from(method);
          if (ToolSearchToolCallingAdvisor.ALWAYS_LOADED.contains(definition.name())) {
            raw.add(definition);
          }
        }
      }
    }
    for (final Object tool :
        List.of(
            TodoWriteTool.builder().build(),
            FileSystemTools.builder().build(),
            GlobTool.builder().build(),
            GrepTool.builder().build(),
            AskUserQuestionTool.builder()
                .questionHandler(questions -> java.util.Map.of())
                .build())) {
      for (final var callback : ToolCallbacks.from(tool)) {
        if (ToolSearchToolCallingAdvisor.ALWAYS_LOADED.contains(
            callback.getToolDefinition().name())) {
          raw.add(callback.getToolDefinition());
        }
      }
    }
    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(raw);
    final var texts = new ModuleToolTexts("core/tools", LocalizedPrompt.TOOLS_LOCATION, locale);
    return new DescribingToolCallingManager(
            new LocalizingToolCallingManager(delegate, List.of(texts)), messages(locale))
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class));
  }

  /** Name, description and schema as they go on the wire: the schema compact, as JSON is sent. */
  static int size(final ToolDefinition d) {
    return d.name().length() + d.description().length() + compact(d.inputSchema()).length();
  }

  static String compact(final String schema) {
    final var mapper = new tools.jackson.databind.json.JsonMapper();
    return mapper.writeValueAsString(mapper.readTree(schema));
  }

  private static CoreMessages messages(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    return new CoreMessages(source, new SpringAgentProperties(null, locale, null, null));
  }

  /**
   * Every always-loaded tool goes with every request, and on a self-hosted model every token of
   * context slows every token it writes, so their descriptions say what calling one needs and leave
   * the how-to to the system prompt. The bound is what that came to, with a little room.
   */
  @ParameterizedTest
  @CsvSource({"en, 19000", "zh-CN, 12500"})
  @DisplayName("the always-loaded tools stay within a size budget in every language")
  void withinBudget(final String tag, final int budget) {
    final var definitions = alwaysLoaded(Locale.forLanguageTag(tag));
    final var total = definitions.stream().mapToInt(AlwaysLoadedToolSizeTest::size).sum();
    System.out.printf("ALWAYS-LOADED %s: %d tools, %d chars%n", tag, definitions.size(), total);

    assertThat(definitions).hasSize(18);
    assertThat(total).isLessThan(budget);
  }
}
