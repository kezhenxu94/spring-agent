package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import me.kezhenxu94.springagent.core.tools.i18n.LocalizingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuBitableTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuChatTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuDocTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuImportExportTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuSheetTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuTools;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuWikiTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Every tool this module offers, put through the real manager, and checked for English.
 *
 * <p>The parity test says each key names something real; this says the other half — that what
 * actually reaches the model is the translation rather than the annotation. Both are needed: a key
 * can be valid and still never be applied, which is exactly the failure that got shipped once.
 *
 * <p>The tools are built with null collaborators on purpose. Nothing here calls a tool; deriving a
 * callback only reflects over the methods and their annotations, which is all a definition is made
 * of.
 */
class FeishuToolsLocalizedEndToEndTest {

  /** Any run of CJK, which is what "this was translated" looks like without hardcoding a phrase. */
  private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

  private static List<ToolDefinition> localizedDefinitionsOf(final Object... tools) {
    final var definitions =
        java.util.Arrays.stream(tools)
            .flatMap(tool -> java.util.Arrays.stream(ToolCallbacks.from(tool)))
            .map(callback -> callback.getToolDefinition())
            .toList();

    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(definitions);

    final var texts =
        new ModuleToolTexts("feishu/tools", FeishuGuides.TOOLS_LOCATION, Locale.of("zh", "CN"));
    return new LocalizingToolCallingManager(delegate, List.of(texts))
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class));
  }

  private static List<ToolDefinition> everyFeishuTool() {
    return localizedDefinitionsOf(
        new FeishuTools(null, null, null, null, null, null, null, null, null, null),
        new FeishuChatTools(null, null),
        new FeishuWikiTools(null),
        new FeishuSheetTools(null, null, null, null, null, null),
        new FeishuDocTools(null, null, null, null, null, null, null, null, null),
        new FeishuBitableTools(null, null, null, null, null, null),
        new FeishuImportExportTools(null, null, null));
  }

  @Test
  @DisplayName("every Feishu tool describes itself to the model in Chinese")
  void everyDescriptionIsTranslated() {
    final var definitions = everyFeishuTool();

    assertThat(definitions).as("the tools were not derived at all").isNotEmpty();
    final var english =
        definitions.stream()
            .filter(definition -> !CHINESE.matcher(definition.description()).find())
            .map(ToolDefinition::name)
            .toList();
    assertThat(english).as("still described in English").isEmpty();
  }

  @Test
  @DisplayName("and so does every one of their parameters")
  void everyParameterIsTranslated() {
    final var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    final var english = new java.util.ArrayList<String>();

    for (final var definition : everyFeishuTool()) {
      final var root = mapper.readTree(definition.inputSchema());
      final var properties = root.get("properties");
      if (properties == null) {
        continue;
      }
      properties
          .propertyNames()
          .forEach(
              parameter -> {
                final var described = properties.get(parameter).get("description");
                if (described != null && !CHINESE.matcher(described.asString()).find()) {
                  english.add(definition.name() + "." + parameter);
                }
              });
    }
    assertThat(english).as("still described in English").isEmpty();
  }

  @Test
  @DisplayName("the tool names are untouched, being what everything else matches on")
  void namesAreUntouched() {
    assertThat(everyFeishuTool())
        .extracting(ToolDefinition::name)
        .allSatisfy(name -> assertThat(name).matches("[A-Za-z][A-Za-z0-9_]*"));
  }
}
