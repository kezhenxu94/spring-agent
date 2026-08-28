package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.core.tools.DateTimeTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The whole chain on real objects: a tool this module declares, the callback Spring AI derives from
 * its annotations, the bundle and prompt files core actually ships, and the manager a run really
 * uses.
 *
 * <p>Worth having beside the unit tests because each of those stubs out the piece next to it, and
 * the failure this catches is the one none of them can — a translation that is well-formed, names a
 * tool that exists, and still never reaches the model because the wiring in between does not hold.
 */
class CoreToolsLocalizedEndToEndTest {

  private static ToolDefinition localized(final Object tool, final Locale locale) {
    final var callback = ToolCallbacks.from(tool)[0];
    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(List.of(callback.getToolDefinition()));

    final var texts = new ModuleToolTexts("core/tools", LocalizedPrompt.TOOLS_LOCATION, locale);
    return new LocalizingToolCallingManager(delegate, List.of(texts))
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class))
        .getFirst();
  }

  @Test
  @DisplayName("a translated tool reaches the model in the workspace's language, keeping its name")
  void translated() {
    final var definition = localized(new DateTimeTool(), Locale.of("zh", "CN"));

    assertThat(definition.name()).isEqualTo("CurrentDateTime");
    assertThat(definition.description()).isEqualTo("当前的日期和时间，也是推算任何相对时间的依据，例如“明天”或“下个月”。");
  }

  @Test
  @DisplayName("the same tool in a language nobody translated keeps the English it declares")
  void untranslated() {
    final var definition = localized(new DateTimeTool(), Locale.FRANCE);

    assertThat(definition.name()).isEqualTo("CurrentDateTime");
    assertThat(definition.description()).startsWith("The current date and time");
  }

  /**
   * The point of localizing on the definition rather than in the tool classes: a tool whose
   * annotations live in somebody else's jar is translated exactly like one of ours, with no change
   * to the library and no release to wait for.
   */
  @Test
  @DisplayName("a tool from spring-ai-agent-utils localizes the same way, from core's own files")
  void upstreamTool() {
    final var definition =
        localized(
            org.springaicommunity.agent.tools.TodoWriteTool.builder().build(),
            Locale.of("zh", "CN"));

    assertThat(definition.name()).isEqualTo("TodoWrite");
    // A phrase from the body rather than the opening words: the opening is the part most likely to
    // be
    // reworded by a later pass over the translation, and this test is about the wiring, not the
    // prose.
    assertThat(definition.description()).contains("结构化的任务清单");
  }

  /**
   * The tool the model is actually offered on every iteration once the tool search is on.
   *
   * <p>{@code ToolSearchToolCallingAdvisor.prepareIteration} replaces the request's callbacks with
   * just its own tool plus whatever a search has already named, so this one description is most of
   * what the model ever reads about its tools — and it belongs to neither this project nor
   * spring-ai-agent-utils. Left untranslated it makes a fully translated deployment look untouched,
   * which is exactly how this was found.
   */
  @Test
  @DisplayName("the tool search's own tool is localized, being the one offered every iteration")
  void toolSearchTool() {
    final var definition =
        localized(
            new org.springframework.ai.tool.toolsearch.ToolSearchTool(null, 5),
            Locale.of("zh", "CN"));

    assertThat(definition.name()).isEqualTo("toolSearchTool");
    assertThat(definition.description()).startsWith("在工具库里搜索工具");
    assertThat(definition.inputSchema())
        .as("its parameters travel in the schema, and are read as closely as the description")
        .contains("用自然语言描述你需要的工具能力");
  }

  /**
   * The skill tool, which is localized through its template rather than through the definition,
   * since the library composes the list of installed skills into it. This is the assertion that
   * both halves happened: the prose is translated and the list is still there.
   */
  @Test
  @DisplayName("the skill tool is translated through its template, keeping the skills list")
  void skillTool(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path skills)
      throws java.io.IOException {
    final var demo = skills.resolve("a-demo-skill");
    java.nio.file.Files.createDirectories(demo);
    java.nio.file.Files.writeString(
        demo.resolve("SKILL.md"),
        "---\nname: a-demo-skill\ndescription: what it does\n---\nbody\n");

    final var builder =
        org.springaicommunity.agent.tools.SkillsTool.builder()
            .addSkillsDirectories(java.util.List.of(skills.toString()));
    LocalizedPrompt.findText(
            me.kezhenxu94.springagent.core.tools.AgentToolsProvider.SKILL_TOOL_PROMPT,
            Locale.of("zh", "CN"))
        .ifPresent(builder::toolDescriptionTemplate);

    final var description = builder.build().getToolDefinition().description();

    assertThat(description).as("the prose is translated").contains("在当前对话中执行一个技能");
    assertThat(description)
        .as("and the skills the library formatted in are still there")
        .contains("a-demo-skill");
    assertThat(description).as("the slot itself was consumed").doesNotContain("%s");
  }
}
