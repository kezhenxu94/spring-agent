package me.kezhenxu94.springagent.integration.feishu.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a translation which exists is also a translation which is <em>finished</em>.
 *
 * <p>This exists because of a real failure, not a hypothetical one: a {@code TodoWrite} translation
 * was shipped that rendered the first two sentences of a nine-thousand-character description and
 * then said, in Chinese, that the rest was still to come. Every other check passed it. The parity
 * test only asks whether a key names something real; the end-to-end test only asks whether the text
 * that reaches the model contains any Chinese at all — and two Chinese sentences satisfy that as
 * well as two thousand do.
 *
 * <p>So this asks the two questions those cannot:
 *
 * <ul>
 *   <li>does the translation still carry a note to the developer? A model reads whatever is there,
 *       so "to be completed" is an instruction as far as it is concerned.
 *   <li>is it implausibly short for what it replaces? Chinese renders the same content in fewer
 *       characters than English, so a faithful translation lands somewhere around a third to two
 *       thirds of the original. A twentieth is not a translation.
 * </ul>
 */
class FeishuToolTranslationsCompleteTest {

  /**
   * Markers of work left unfinished, in either language. Deliberately literal: the point is to
   * catch the note a translator leaves themselves, and those are written in a handful of
   * predictable ways.
   */
  private static final Pattern UNFINISHED =
      // The Latin markers are matched as whole uppercase words on purpose. Case-insensitively,
      // "XXX"
      // matches the oc_xxx and om_xxx that these descriptions legitimately use as example ids, and
      // "xxx.feishu.cn" in a sample link — which is how the first version of this test reported
      // five
      // perfectly good translations as abandoned.
      Pattern.compile("待补|待翻译|待完成|尚未翻译|\\b(?:TODO|TBD|FIXME|XXX)\\b");

  /**
   * How short a translation may be relative to the English it replaces.
   *
   * <p>Chinese is denser, so a faithful rendering is shorter — measured across this repository the
   * ratio sits between about 0.3 and 0.9. A quarter is comfortably below anything real and far
   * above the two hundredths a stub scores, so it catches abandoned work without arguing about
   * style.
   */
  private static final double SHORTEST_PLAUSIBLE = 0.20;

  /**
   * Below this, English is a label rather than prose and the ratio says nothing useful.
   *
   * <p>Set by what actually happens at the short end. "The content to write to the file" is
   * thirty-two characters and renders faithfully as six; "Whether advanced permissions are on; left
   * unchanged when left out" is sixty-five and renders as sixteen. Terse spec text carries almost
   * no grammar to preserve, so the ratio there says nothing. Above this length there is prose to
   * lose, and losing it is what this catches: the stub that prompted the test scored 0.018, and a
   * genuinely half-finished description scored 0.17.
   */
  private static final int TOO_SHORT_TO_JUDGE = 80;

  private static java.util.Map<String, ToolTextsInventory.English> english;
  private static ModuleToolTexts texts;

  @BeforeAll
  static void inventory() throws Exception {
    english =
        ToolTextsInventory.englishOf("me.kezhenxu94.springagent.integration.feishu", List.of());
    texts = new ModuleToolTexts("feishu/tools", FeishuGuides.TOOLS_LOCATION, Locale.of("zh", "CN"));
  }

  @Test
  @DisplayName("the inventory found the English, so the rest cannot pass vacuously")
  void inventoryIsNotEmpty() {
    assertThat(english).isNotEmpty();
  }

  @Test
  @DisplayName("no translation carries a note saying it is unfinished")
  void noneIsAPlaceholder() {
    assertSoftly(
        softly ->
            english
                .keySet()
                .forEach(
                    tool -> {
                      final var translated = texts.description(tool);
                      if (translated != null) {
                        softly
                            .assertThat(UNFINISHED.matcher(translated).find())
                            .as("%s's description is left unfinished: %s", tool, translated)
                            .isFalse();
                      }
                      english
                          .get(tool)
                          .parameters()
                          .keySet()
                          .forEach(
                              parameter -> {
                                final var value = texts.parameter(tool, parameter);
                                if (value != null) {
                                  softly
                                      .assertThat(UNFINISHED.matcher(value).find())
                                      .as("%s.%s is left unfinished: %s", tool, parameter, value)
                                      .isFalse();
                                }
                              });
                    }));
  }

  @Test
  @DisplayName("no translation is a fraction of the length of what it replaces")
  void noneIsATruncation() {
    assertSoftly(
        softly ->
            english.forEach(
                (tool, declared) -> {
                  final var translated = texts.description(tool);
                  if (translated != null && declared.description().length() >= TOO_SHORT_TO_JUDGE) {
                    softly
                        .assertThat((double) translated.length() / declared.description().length())
                        .as(
                            "%s's description is %d characters against %d of English, which reads"
                                + " as abandoned rather than translated",
                            tool, translated.length(), declared.description().length())
                        .isGreaterThanOrEqualTo(SHORTEST_PLAUSIBLE);
                  }
                  declared
                      .parameters()
                      .forEach(
                          (parameter, source) -> {
                            final var value = texts.parameter(tool, parameter);
                            if (value != null && source.length() >= TOO_SHORT_TO_JUDGE) {
                              softly
                                  .assertThat((double) value.length() / source.length())
                                  .as(
                                      "%s.%s is %d characters against %d of English",
                                      tool, parameter, value.length(), source.length())
                                  .isGreaterThanOrEqualTo(SHORTEST_PLAUSIBLE);
                            }
                          });
                }));
  }
}
