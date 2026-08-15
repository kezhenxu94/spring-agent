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
 * <p>Spring AI finds a tool by reflecting for {@code @Tool} over declared methods, and {@code
 * ChatClient.tools(...)} rejects an object with none — so without these a native image fails the
 * run rather than quietly losing a tool.
 *
 * <p>In core rather than in an integration because core composes these, so every integration built
 * as a native image needs the same set. A tool this project declares itself is found through
 * {@code @AgentTool} and already gets hints from Spring's own AOT processing.
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
    // The tool-search advisor's system-prompt suffix, read while the advisor is being built and so
    // fatal to the run when absent. Both files, because which one it reads depends on how many
    // tools are indexed.
    hints.resources().registerPattern("DEFAULT_SYSTEM_PROMPT_SUFFIX*.md");

    // The advisor's own tool. By name because the advisor is switched on by a property and its
    // module need not be on the classpath at all.
    hints
        .reflection()
        .registerTypeIfPresent(
            classLoader,
            "org.springframework.ai.tool.toolsearch.ToolSearchTool",
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);

    for (final var type : TOOL_TYPES) {
      register(hints, type);
    }
  }

  /**
   * Registers {@code type} and everything nested inside it, to any depth.
   *
   * <p>Depth matters: a tool's parameters are records nested inside it and those nest further, so
   * {@code AskUserQuestionTool.Question.Option} is two levels down. A record whose components are
   * unregistered cannot be read at all in a native image, which fails the tool call rather than
   * leaving the value blank.
   */
  private static void register(final RuntimeHints hints, final Class<?> type) {
    hints
        .reflection()
        .registerType(
            type,
            // Methods to find and call the @Tool ones and to read a record's components;
            // constructors for the nested builders; fields for the generated JSON schema.
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.ACCESS_DECLARED_FIELDS);
    for (final var nested : type.getDeclaredClasses()) {
      register(hints, nested);
    }
  }
}
