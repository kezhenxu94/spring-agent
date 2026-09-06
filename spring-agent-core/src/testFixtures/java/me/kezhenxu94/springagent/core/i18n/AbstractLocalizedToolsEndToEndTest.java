package me.kezhenxu94.springagent.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import me.kezhenxu94.springagent.core.tools.i18n.LocalizingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Every tool a module offers, put through the real manager, and checked for English.
 *
 * <p>The parity test says each key names something real; this says the other half — that what
 * actually reaches the model is the translation rather than the annotation. Both are needed: a key
 * can be valid and still never be applied, which is exactly the failure that got shipped once.
 *
 * <p>The whole definition is checked, description and input schema alike. A parameter description
 * lives in the schema and is as much of the prompt as the description is: a tool offered with a
 * Chinese summary and eight English parameter notes is still mostly English on the wire, and it is
 * the wire that decides which language the model thinks in.
 *
 * <p>Tools are built with null collaborators in the subclasses on purpose. Nothing here calls a
 * tool; deriving a callback only reflects over the methods and their annotations, which is all a
 * definition is made of.
 */
public abstract class AbstractLocalizedToolsEndToEndTest {

  /** Any run of CJK, which is what "this was translated" looks like without hardcoding a phrase. */
  protected static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

  protected abstract String bundleBase();

  protected abstract String promptDirectory();

  /** One instance of every class in this module that declares {@code @Tool} methods. */
  protected abstract List<Object> tools();

  /**
   * Parameters whose description is deliberately left as it is — a name the model has to type back
   * rather than prose it reads, such as an enum of API values.
   */
  protected List<String> untranslatedParametersAllowed() {
    return List.of();
  }

  protected List<ToolDefinition> localizedDefinitions() {
    final var definitions =
        tools().stream()
            .flatMap(tool -> Arrays.stream(ToolCallbacks.from(tool)))
            .map(callback -> callback.getToolDefinition())
            .toList();

    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(definitions);

    final var texts =
        new ModuleToolTexts(bundleBase(), promptDirectory(), Locale.SIMPLIFIED_CHINESE);
    return new LocalizingToolCallingManager(delegate, List.of(texts))
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class));
  }

  @Test
  @DisplayName("every tool describes itself to the model in the workspace's language")
  void everyDescriptionIsTranslated() {
    final var definitions = localizedDefinitions();

    assertThat(definitions).as("the tools were not derived at all").isNotEmpty();

    final var english =
        definitions.stream()
            .filter(definition -> !CHINESE.matcher(definition.description()).find())
            .map(ToolDefinition::name)
            .sorted()
            .toList();

    assertThat(english)
        .as("these reach the model in English, whatever the workspace asked for")
        .isEmpty();
  }

  @Test
  @DisplayName("and every parameter description in the schema it is offered with")
  void everyParameterDescriptionIsTranslated() {
    final var allowed = untranslatedParametersAllowed();

    final var english =
        localizedDefinitions().stream()
            .flatMap(
                definition ->
                    SchemaDescriptions.of(definition.inputSchema()).entrySet().stream()
                        .filter(entry -> !CHINESE.matcher(entry.getValue()).find())
                        .map(entry -> definition.name() + "." + entry.getKey()))
            .filter(name -> !allowed.contains(name))
            .sorted()
            .toList();

    assertThat(english)
        .as(
            "a parameter description is part of the prompt the model reads; these are still"
                + " English on the wire")
        .isEmpty();
  }
}
