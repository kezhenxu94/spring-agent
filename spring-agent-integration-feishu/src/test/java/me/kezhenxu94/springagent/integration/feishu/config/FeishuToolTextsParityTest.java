package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That every translation this module ships still names a tool, and a parameter, that exists.
 *
 * <p>The same check core makes of its own, and it has to be a second test rather than a case in
 * that one: only this module can enumerate its own tool classes, and there are more tools here than
 * in core. Nothing else would notice a renamed tool — the override just stops matching, the English
 * comes back, and the file that was meant to replace it sits there looking translated.
 */
class FeishuToolTextsParityTest {

  static final String BUNDLE = "feishu/tools";

  private static Map<String, Set<String>> tools;

  @BeforeAll
  static void inventory() throws Exception {
    tools = ToolTextsInventory.toolsOf("me.kezhenxu94.springagent.integration.feishu", List.of());
  }

  @Test
  @DisplayName("the inventory found the tools, so an empty one cannot pass the rest vacuously")
  void inventoryIsNotEmpty() {
    assertThat(tools).isNotEmpty();
    assertThat(tools)
        .containsKeys("FeishuSendMessage", "FeishuCreateBitable", "FeishuDocBlockGuide");
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
    final var files = ToolTextsInventory.descriptionFiles(FeishuGuides.TOOLS_LOCATION);

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
                        .as("%s carries a locale that does not parse", file.filename())
                        .isNotNull()
                        .extracting(Locale::getLanguage)
                        .isNotEqualTo("und");
                  }
                }));
  }

  @Test
  @DisplayName("everything this module marks as translated actually resolves to something")
  void everyTranslationTakesEffect() {
    final var texts =
        new ModuleToolTexts(BUNDLE, FeishuGuides.TOOLS_LOCATION, Locale.of("zh", "CN"));

    for (final var entry : tools.entrySet()) {
      final var tool = entry.getKey();
      if (!texts.covers(tool)) {
        continue;
      }
      final var resolves =
          texts.description(tool) != null
              || entry.getValue().stream().anyMatch(p -> texts.parameter(tool, p) != null);
      assertThat(resolves).as("%s is covered but nothing about it resolves", tool).isTrue();
    }
  }
}
