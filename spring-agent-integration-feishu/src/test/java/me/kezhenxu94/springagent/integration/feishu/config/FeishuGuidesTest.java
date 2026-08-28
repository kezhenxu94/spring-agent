package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Locale;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeishuGuidesTest {

  /**
   * Every language the module ships, so a translation that was added without its siblings — or one
   * whose file was misnamed and so is silently never picked — fails here.
   */
  @ParameterizedTest
  @ValueSource(strings = {"en", "zh_CN"})
  @DisplayName("every guide loads in every language the module ships, and none is blank")
  void everyGuideLoads(final String tag) {
    final var parts = tag.split("_");
    final var locale = parts.length == 1 ? Locale.of(parts[0]) : Locale.of(parts[0], parts[1]);

    final var guides = new FeishuGuides(locale);

    assertThat(
            java.util.List.<Function<FeishuGuides, String>>of(
                FeishuGuides::bitableFieldReference,
                FeishuGuides::bitableFilterGuide,
                FeishuGuides::docBlockGuide,
                FeishuGuides::docBlockContentReference,
                FeishuGuides::sheetDataFormats))
        .allSatisfy(guide -> assertThat(guide.apply(guides)).isNotBlank());
  }

  @Test
  @DisplayName(
      "a language nobody translated falls back to the base file rather than failing a call")
  void untranslatedLanguage() {
    assertThatCode(() -> new FeishuGuides(Locale.FRANCE)).doesNotThrowAnyException();
    assertThat(new FeishuGuides(Locale.FRANCE).sheetDataFormats())
        .isEqualTo(new FeishuGuides(Locale.ENGLISH).sheetDataFormats());
  }

  /**
   * These are handed to the model as a tool's result and never rendered as a template, so their
   * braces are literal and have to stay single. The neighbouring rule for {@code core/prompts} is
   * the opposite — the memory prompt goes through a brace-delimited template and has a test
   * insisting its braces are doubled — so this says out loud which of the two applies here.
   */
  @Test
  @DisplayName("the guides keep the single braces their JSON examples are written with")
  void bracesAreLiteral() {
    assertThat(new FeishuGuides(Locale.ENGLISH).sheetDataFormats())
        .contains("{\"type\": \"formula\"")
        .doesNotContain("{{");
  }
}
