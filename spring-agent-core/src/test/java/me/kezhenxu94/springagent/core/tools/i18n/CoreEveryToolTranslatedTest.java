package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import me.kezhenxu94.springagent.core.i18n.AbstractEveryToolTranslatedTest;
import org.springframework.util.ClassUtils;

/**
 * {@inheritDoc}
 *
 * <p>Core is the module every run draws on, so a tool left in English here is English in every
 * deployment rather than in one surface. The library's tools count as core's: they are offered by
 * every run and translated from core's own files, which is the whole point of localizing on the
 * definition rather than in the tool class.
 */
class CoreEveryToolTranslatedTest extends AbstractEveryToolTranslatedTest {

  @Override
  protected String basePackage() {
    return "me.kezhenxu94.springagent.core";
  }

  @Override
  protected String bundleBase() {
    return "core/tools";
  }

  @Override
  protected String promptDirectory() {
    return LocalizedPrompt.TOOLS_LOCATION;
  }

  /**
   * The library's tools that core actually offers, which is not the same list as the one the native
   * hints register. Memory is not among them any more, and neither is TodoWrite: {@code
   * core.memory.MemoryTools} and {@code core.tools.TodoWriteTool} are core's own classes, so the
   * package scan finds them without being told. The hints' list: that list also carries {@code
   * ShellTools}, whose three tools belong to the local shell backend and are translated in its own
   * bundle, and {@code GlobTool}, {@code GrepTool} and {@code ListDirectoryTool}, which no bean
   * here publishes.
   */
  private static final List<Class<?>> OFFERED_BY_CORE =
      List.of(
          org.springaicommunity.agent.tools.AskUserQuestionTool.class,
          org.springaicommunity.agent.tools.FileSystemTools.class);

  @Override
  protected List<Class<?>> extraTypes() {
    final var extras = new ArrayList<Class<?>>(OFFERED_BY_CORE);
    // The tool search's own tool, by name for the same reason the hints register it by name: the
    // advisor is switched on by a property and its module need not be on the classpath.
    try {
      extras.add(
          ClassUtils.forName(
              "org.springframework.ai.tool.toolsearch.ToolSearchTool",
              CoreEveryToolTranslatedTest.class.getClassLoader()));
    } catch (ClassNotFoundException absent) {
      // Then there is nothing to check, and the parity test is what says so.
    }
    return List.copyOf(extras);
  }

  @Override
  protected Set<String> untranslatedAllowed() {
    // Nothing. The skills are not on the list above any more: they are one FunctionToolCallback per
    // installed skill, named and described by the skill itself, so there is no static text to
    // translate and no name to file a translation under. What this deployment does own is the
    // template around each description — core/prompts/skill-tool_zh_CN.md, which is a prompt rather
    // than a tool text, and CoreToolsLocalizedEndToEndTest is what checks it lands.
    return Set.of();
  }
}
