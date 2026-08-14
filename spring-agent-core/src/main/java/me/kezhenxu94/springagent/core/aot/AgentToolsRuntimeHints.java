package me.kezhenxu94.springagent.core.aot;

import java.util.List;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ListDirectoryTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for the tools that come from spring-ai-agent-utils.
 *
 * <p>Spring AI finds a tool by reflecting over a class's declared methods looking for
 * {@code @Tool}, so in a native image a tool class without method metadata simply appears to have
 * none. What that looks like is not a missing tool but a failed run: {@code ChatClient.tools(...)}
 * rejects an object with no tool methods outright, so the first turn of the first native build died
 * with
 *
 * <pre>No @Tool annotated methods found in ...AutoMemoryTools</pre>
 *
 * <p>In core rather than in an integration because core is what composes these — {@code
 * AgentToolsProvider} for most of them and {@code SpringAgent} for the memory advisor's — so every
 * integration built as a native image needs exactly this set.
 *
 * <p>The library's own beans are not covered here: a tool this project declares is found through
 * {@code @AgentTool}, and Spring's AOT processing already writes hints for a bean's own type.
 */
public class AgentToolsRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * Every tool type spring-ai-agent-utils can hand to a run. The two search tools it also ships,
   * Brave and web fetch, are left out: nothing here constructs them.
   */
  private static final List<Class<?>> TOOL_TYPES =
      List.of(
          AutoMemoryTools.class,
          TodoWriteTool.class,
          AskUserQuestionTool.class,
          FileSystemTools.class,
          SkillsTool.class,
          ShellTools.class,
          GlobTool.class,
          GrepTool.class,
          ListDirectoryTool.class);

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // Spring AI's tool-search advisor reads its system-prompt suffix from a classpath resource at
    // the root of spring-ai-tool-search-tool, and nothing registers it. Which of the two files it
    // reads depends on how many tools are indexed, so both are registered — the failure is a
    // FileNotFoundException while the advisor is being built, which takes the run with it.
    hints.resources().registerPattern("DEFAULT_SYSTEM_PROMPT_SUFFIX*.md");

    // The advisor's own tool, the one that lets the model search for the others. By name because
    // the advisor is switched on by a property and its module need not be on the classpath at all;
    // registerTypeIfPresent is a no-op when it is absent.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.springframework.ai.tool.toolsearch.ToolSearchTool",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);

    for (final var type : TOOL_TYPES) {
      hints
          .reflection()
          .registerType(
              type,
              // Methods to find the @Tool ones and to call them; constructors because several are
              // built through a nested builder; fields because the JSON schema generator reads the
              // parameter records' components.
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS,
              MemberCategory.ACCESS_DECLARED_FIELDS);
      // The parameter and result records live as nested types — Question, Todos, TodoItem and so
      // on — and the schema generated for a tool call is built from them by reflection too.
      for (final var nested : type.getDeclaredClasses()) {
        hints
            .reflection()
            .registerType(
                nested,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.ACCESS_DECLARED_FIELDS);
      }
    }
  }
}
