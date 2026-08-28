package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModuleToolTextsTest {

  private static final String BUNDLE = "i18n/tools";
  private static final String PROMPTS = "i18n/prompts/tools/";

  private static ModuleToolTexts textsIn(final Locale locale) {
    return new ModuleToolTexts(BUNDLE, PROMPTS, locale);
  }

  @Test
  @DisplayName("a description is the most specific translation of the file named for the tool")
  void description() {
    assertThat(textsIn(Locale.GERMANY).description("Sample")).isEqualTo("Was das Werkzeug tut.");
  }

  @Test
  @DisplayName("a tool nobody translated keeps what it declares, which is to say null here")
  void untranslatedTool() {
    assertThat(textsIn(Locale.GERMANY).description("NoSuchTool")).isNull();
    assertThat(textsIn(Locale.FRANCE).description("Sample")).isNull();
  }

  /**
   * A blank file would not fail and would not leave the English alone either: the tool definition
   * builder makes a description up out of the tool's name when it is handed one with no text in it.
   */
  @Test
  @DisplayName("a blank translation counts as absent rather than as an empty description")
  void blankDescription() {
    assertThat(textsIn(Locale.GERMANY).description("Blank")).isNull();
  }

  @Test
  @DisplayName("the exact locale wins a key it states and inherits every key it does not")
  void parametersMergeAlongTheChain() {
    final var exact = textsIn(Locale.GERMANY);
    assertThat(exact.parameter("Sample", "second")).isEqualTo("zweiter Parameter, genau");
    assertThat(exact.parameter("Sample", "first"))
        .as("the base file's English correction is inherited")
        .isEqualTo("an English correction every locale inherits");

    assertThat(textsIn(Locale.GERMAN).parameter("Sample", "second")).isEqualTo("zweiter Parameter");
  }

  @Test
  @DisplayName("an untranslated parameter is absent, not blank")
  void untranslatedParameter() {
    assertThat(textsIn(Locale.GERMANY).parameter("Sample", "third")).isNull();
    assertThat(textsIn(Locale.GERMANY).parameter("NoSuchTool", "first")).isNull();
  }

  @Test
  @DisplayName("covers answers for a description alone and for a parameter alone")
  void covers() {
    final var texts = textsIn(Locale.GERMANY);
    assertThat(texts.covers("Sample")).isTrue();
    assertThat(texts.covers("NoSuchTool")).isFalse();

    // Nothing but a description, in German only.
    assertThat(textsIn(Locale.FRANCE).covers("Sample"))
        .as("the base file still names a parameter of it")
        .isTrue();
  }

  /**
   * The trap a {@link java.util.ResourceBundle} would have walked into: its default lookup consults
   * the host's locale before the base bundle, so an English deployment on a German host would serve
   * German. Reading the properties directly is what avoids it, and this is what says so.
   */
  @Test
  @DisplayName("the host's language is not consulted for a locale that has no translation")
  void doesNotFallBackToTheHost() {
    final var host = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      final var french = textsIn(Locale.FRANCE);
      assertThat(french.description("Sample")).isNull();
      assertThat(french.parameter("Sample", "second")).isNull();
    } finally {
      Locale.setDefault(host);
    }
  }
}
