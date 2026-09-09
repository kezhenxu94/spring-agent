package me.kezhenxu94.springagent.core.aot;

import java.util.List;
import me.kezhenxu94.springagent.core.tools.SkillsTool;
import me.kezhenxu94.springagent.core.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ListDirectoryTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for the tools a run is handed rather than told about by a bean definition.
 *
 * <p>Spring AI finds a tool by reflecting for {@code @Tool} over declared methods, and {@code
 * ChatClient.tools(...)} rejects an object with none — so without these a native image fails the
 * run rather than quietly losing a tool.
 *
 * <p>In core rather than in an integration because core composes these, so every integration built
 * as a native image needs the same set. A tool this project declares as a bean is found through
 * {@code @AgentTool} and already gets hints from Spring's own AOT processing — which is why {@code
 * TodoWriteTool} is in this list despite being core's own class: nothing declares it a bean, {@code
 * AgentToolsProvider} builds one per run, so AOT never sees it.
 */
public class AgentToolsRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * Every tool type spring-ai-agent-utils can hand to a run, plus core's two forks of ones it ships
   * — {@code TodoWriteTool} and {@code SkillsTool}. The two search tools the library also ships,
   * Brave and web fetch, are left out: nothing here constructs them.
   *
   * <p>Public because it is this module's one statement of which tools it takes from that library,
   * and the test that no translation names a tool that no longer exists reads the same list. Adding
   * an upstream tool then updates the hints and the translations together, rather than leaving the
   * second to be noticed later.
   */
  public static final List<Class<?>> TOOL_TYPES =
      List.of(
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
    // Core's own prompts: the memory one MemoryToolsAdvisor is built with, and the
    // tool-search suffix ToolSearchAdvisorDefaults reads into a property. Both are read while a run
    // is being assembled, so an image without them fails the run rather than losing a paragraph.
    hints.resources().registerPattern("core/prompts/*.md");

    // Each tool's description in the workspace's language, one file per tool. A pattern of its own
    // because the one above does not cross a directory separator.
    hints.resources().registerPattern("core/prompts/tools/*.md");

    // And the local shell backend's, which are core's files but not core's tools: the library
    // declares Bash, BashOutput and KillShell, and all three shell backends translate those names
    // in a bundle of their own so that only the active backend's wording is ever served.
    hints.resources().registerPattern("shell-local/prompts/tools/*.md");
    hints.resources().registerPattern("shell-local/tools.properties");
    hints.resources().registerPattern("shell-local/tools_*.properties");

    // And each parameter's. A plain resource pattern, not registerResourceBundle: ModuleToolTexts
    // reads these as resources precisely so as not to go through a ResourceBundle, which would
    // consult the host's locale before the base file.
    hints.resources().registerPattern("core/tools.properties");
    hints.resources().registerPattern("core/tools_*.properties");

    // The library default that prompt falls back to, still reachable by an application that sets a
    // prompt of its own to blank, or that swaps the advisor for one built by hand. The memory
    // advisor's equivalent is gone with the fork: MemoryToolsAdvisor has no built-in prompt to fall
    // back to, so nothing can reach the library's copy of one any more.
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
