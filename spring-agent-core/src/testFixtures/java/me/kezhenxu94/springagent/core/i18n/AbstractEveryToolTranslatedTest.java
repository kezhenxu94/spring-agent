package me.kezhenxu94.springagent.core.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gap parity cannot see: a tool with no translation at all.
 *
 * <p>Parity asks whether every translation names a real tool. This asks the converse — whether
 * every real tool has one — and it is the converse that decides what language the model reads on
 * every turn, since a tool nobody translated reaches it in the English its annotation declares.
 *
 * <p>A separate class from the parity check on purpose. Translation is an override, and a module
 * may legitimately ship a tool with none; a module that has finished the work adopts this and keeps
 * it finished, and one that has not does not pretend otherwise.
 */
public abstract class AbstractEveryToolTranslatedTest {

  protected abstract String basePackage();

  protected abstract String bundleBase();

  protected abstract String promptDirectory();

  protected List<Class<?>> extraTypes() {
    return List.of();
  }

  /** Tools whose English is deliberately what the model is offered. */
  protected Set<String> untranslatedAllowed() {
    return Set.of();
  }

  private ModuleToolTexts texts() {
    return new ModuleToolTexts(bundleBase(), promptDirectory(), Locale.SIMPLIFIED_CHINESE);
  }

  @Test
  @DisplayName("every tool this module offers has a description in the workspace's language")
  void everyToolIsTranslated() throws Exception {
    final var texts = texts();
    final var untranslated =
        ToolTextsInventory.toolsOf(basePackage(), extraTypes()).keySet().stream()
            .filter(tool -> !untranslatedAllowed().contains(tool))
            .filter(tool -> texts.description(tool) == null)
            .sorted()
            .toList();

    assertThat(untranslated)
        .as(
            "these describe themselves to the model in English however the workspace is"
                + " configured; add %s<ToolName>_zh_CN.md",
            promptDirectory())
        .isEmpty();
  }

  /**
   * And the same for what a tool's parameters say, which is half the text a tool definition carries
   * and the half a model reads while deciding what to pass.
   */
  @Test
  @DisplayName("every parameter a tool takes is described in the workspace's language")
  void everyParameterIsTranslated() throws Exception {
    final var texts = texts();
    final var english = ToolTextsInventory.englishOf(basePackage(), extraTypes());

    assertThat(english).as("the inventory found nothing, so this proves nothing").isNotEmpty();
    assertSoftly(
        softly ->
            english.forEach(
                (tool, declared) -> {
                  if (untranslatedAllowed().contains(tool)) {
                    return;
                  }
                  declared
                      .parameters()
                      .keySet()
                      .forEach(
                          parameter ->
                              softly
                                  .assertThat(texts.parameter(tool, parameter))
                                  .as(
                                      "%s.%s reaches the model in English; add the key to %s",
                                      tool, parameter, bundleBase())
                                  .isNotNull());
                }));
  }
}
