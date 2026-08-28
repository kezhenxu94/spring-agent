package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.aot.AgentToolsRuntimeHints;
import me.kezhenxu94.springagent.core.config.LocalizedPrompt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That every translation core ships still names a tool, and a parameter, that exists.
 *
 * <p>This is the test that keeps the translations from rotting. Nothing else would notice a renamed
 * tool: the override simply stops matching, the English comes back, and the file that was meant to
 * replace it sits there looking translated. There is deliberately no test in the other direction —
 * a tool with no translation is a supported state, which is the whole point of these being
 * overrides, so "every tool is translated" would be red from the first commit to the last.
 */
class CoreToolTextsParityTest {

  private static final String BUNDLE = "core/tools";

  private static Map<String, Set<String>> tools;

  @BeforeAll
  static void inventory() throws Exception {
    final var extras = new java.util.ArrayList<Class<?>>(AgentToolsRuntimeHints.TOOL_TYPES);
    // The tool search's own tool, by name for the same reason the hints register it by name: the
    // advisor is switched on by a property and its module need not be on the classpath. It belongs
    // in
    // the inventory because it is translated, and it is translated because it is the one tool the
    // model is offered on every iteration once the tool search is on.
    try {
      extras.add(
          org.springframework.util.ClassUtils.forName(
              "org.springframework.ai.tool.toolsearch.ToolSearchTool",
              CoreToolTextsParityTest.class.getClassLoader()));
    } catch (ClassNotFoundException absent) {
      // Then nothing may name it, which is what the assertions below will say.
    }
    final var found =
        new java.util.HashMap<>(
            ToolTextsInventory.toolsOf("me.kezhenxu94.springagent.core", extras));
    // The skill tool, which reflection over @Tool methods cannot see: the library builds it as a
    // FunctionToolCallback, its name and its one parameter coming from a record rather than from an
    // annotated method. Stated here so a key naming it is still checked, and so that a key naming
    // anything else about it is still rejected.
    found.put(
        "Skill",
        java.util.Arrays.stream(
                org.springaicommunity.agent.tools.SkillsTool.SkillsInput.class
                    .getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    tools = java.util.Map.copyOf(found);
  }

  @Test
  @DisplayName("the inventory found the tools, so an empty one cannot pass the rest vacuously")
  void inventoryIsNotEmpty() {
    assertThat(tools).as("core's own tools plus the ones it takes from the library").isNotEmpty();
    assertThat(tools).containsKeys("CurrentDateTime", "Read", "TodoWrite");
  }

  @Test
  @DisplayName("every parameter key names a tool, and a parameter of it, that exists")
  void everyParameterKeyResolves() throws Exception {
    final var keys = ToolTextsInventory.parameterKeys(BUNDLE);

    assertSoftly(
        softly ->
            keys.forEach(
                key -> {
                  final var dot = key.indexOf('.');
                  softly
                      .assertThat(dot)
                      .as("'%s' is keyed <ToolName>.<parameterName>", key)
                      .isGreaterThan(0);
                  if (dot <= 0) {
                    return;
                  }
                  final var tool = key.substring(0, dot);
                  final var parameter = key.substring(dot + 1);
                  softly
                      .assertThat(parameter)
                      .as(
                          "'%s' names something nested; only a parameter of the tool itself is"
                              + " applied, so this key would do nothing",
                          key)
                      .doesNotContain(".");
                  softly
                      .assertThat(tools)
                      .as("'%s' names a tool that no longer exists", key)
                      .containsKey(tool);
                  if (tools.containsKey(tool)) {
                    softly
                        .assertThat(tools.get(tool))
                        .as("'%s' names a parameter %s does not take", key, tool)
                        .contains(parameter);
                  }
                }));
  }

  @Test
  @DisplayName("every description file names a tool that exists, in a locale that parses")
  void everyDescriptionFileResolves() throws Exception {
    final var files = ToolTextsInventory.descriptionFiles(LocalizedPrompt.TOOLS_LOCATION);

    assertSoftly(
        softly ->
            files.forEach(
                file -> {
                  softly
                      .assertThat(tools)
                      .as("%s describes a tool that no longer exists", file.filename())
                      .containsKey(file.toolName());
                  if (file.localeSuffix() != null) {
                    softly
                        .assertThat(file.locale())
                        .as(
                            "%s carries a locale that does not parse; it would never be picked",
                            file.filename())
                        .isNotNull()
                        .extracting(Locale::getLanguage)
                        .isNotEqualTo("und");
                  }
                }));
  }

  /**
   * The failure the three assertions above cannot see: a name that matches and a wiring that does
   * not. This runs the real decorator over the real definition of every translated tool and insists
   * the text actually changed.
   */
  @Test
  @DisplayName("every translation core ships actually reaches the definition")
  void everyTranslationTakesEffect() throws Exception {
    final var locales = List.of(Locale.SIMPLIFIED_CHINESE, Locale.of("zh", "CN"));

    for (final var locale : locales) {
      final var texts = new ModuleToolTexts(BUNDLE, LocalizedPrompt.TOOLS_LOCATION, locale);
      for (final var entry : tools.entrySet()) {
        final var tool = entry.getKey();
        if (!texts.covers(tool)) {
          continue;
        }
        assertThat(
                texts.description(tool) != null
                    || hasTranslatedParameter(texts, entry.getValue(), tool))
            .as("%s is covered in %s but nothing about it resolves", tool, locale)
            .isTrue();
      }
    }
  }

  private static boolean hasTranslatedParameter(
      final ModuleToolTexts texts, final Set<String> parameters, final String tool) {
    return parameters.stream().anyMatch(parameter -> texts.parameter(tool, parameter) != null);
  }
}
