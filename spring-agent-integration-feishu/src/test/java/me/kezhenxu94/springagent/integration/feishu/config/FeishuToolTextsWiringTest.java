package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That this module's translations are actually offered to the runtime.
 *
 * <p>Worth its own test because the failure is silent: core asks every {@code ToolTexts} bean in
 * the context, so a module that stops contributing one does not break — every one of its tools
 * simply goes back to English, with nothing in a log to say so.
 */
class FeishuToolTextsWiringTest {

  @Test
  @DisplayName("the module contributes a source pointed at its own bundle and its own prompt files")
  void contributesItsOwnSource() {
    final var properties =
        new FeishuProperties(
            null, null, null, null, null, null, null, Locale.of("zh", "CN"), null, null);

    final var texts = new FeishuAutoConfiguration().feishuToolTexts(properties);

    assertThat(texts).isInstanceOf(ModuleToolTexts.class);
    assertThat(((ModuleToolTexts) texts).promptDirectory())
        .as("core's own directory would silently serve none of this module's translations")
        .isEqualTo("feishu/prompts/tools/");
    assertThat(((ModuleToolTexts) texts).locale()).isEqualTo(Locale.of("zh", "CN"));
  }

  @Test
  @DisplayName("it follows app.feishu.locale, as everything else this module writes does")
  void followsTheModuleLocale() {
    final var properties =
        new FeishuProperties(null, null, null, null, null, null, null, Locale.FRANCE, null, null);

    assertThat(
            ((ModuleToolTexts) new FeishuAutoConfiguration().feishuToolTexts(properties)).locale())
        .isEqualTo(Locale.FRANCE);
  }
}
