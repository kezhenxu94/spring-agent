package me.kezhenxu94.springagent.integration.telegram.format;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.MessageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That what the model writes arrives as Telegram text whose every span lies inside it.
 *
 * <p>The property that matters is the one Telegram enforces by refusing the message: a span past
 * the end of the text, or of no length, costs the whole edit — the answer simply stops updating.
 * Every case here checks it alongside what it is about.
 */
class TelegramMarkdownTest {

  private static TelegramText render(final String markdown) {
    final var text = TelegramMarkdown.render(markdown);
    for (final var entity : text.entities()) {
      assertThat(entity.length()).as("%s has a length", entity).isPositive();
      assertThat(entity.end())
          .as("%s lies inside the text", entity)
          .isLessThanOrEqualTo(text.length());
    }
    return text;
  }

  private static String spanned(final TelegramText text, final MessageEntity entity) {
    return text.text().substring(entity.offset(), entity.end());
  }

  @Test
  @DisplayName("bold is a span over the words, and the asterisks are gone")
  void shouldTurnEmphasisIntoSpans() {
    final var text = render("this is **important** and *subtle*");

    assertThat(text.text()).isEqualTo("this is important and subtle");
    assertThat(text.entities()).extracting(MessageEntity::type).containsExactly("bold", "italic");
    assertThat(spanned(text, text.entities().getFirst())).isEqualTo("important");
  }

  @Test
  @DisplayName(
      "an answer cut off mid-bold, as a streamed one is, shows its asterisks rather than failing")
  void shouldSurviveAnUnclosedSpan() {
    final var text = render("this is **importa");

    assertThat(text.text()).isEqualTo("this is **importa");
    assertThat(text.entities()).isEmpty();
  }

  @Test
  @DisplayName("a code block keeps its language and its contents verbatim")
  void shouldKeepCodeBlocks() {
    final var text = render("before\n\n```java\nint a = 1 * 2;\n```\n\nafter");

    final var pre = text.entities().stream().filter(e -> e.type().equals("pre")).findFirst();
    assertThat(pre).isPresent();
    assertThat(pre.get().language()).isEqualTo("java");
    assertThat(spanned(text, pre.get())).isEqualTo("int a = 1 * 2;");
  }

  @Test
  @DisplayName("a code block still being written is a code block, not an error")
  void shouldSurviveAnUnfinishedFence() {
    final var text = render("```python\nprint(1)");

    assertThat(text.entities()).extracting(MessageEntity::type).containsExactly("pre");
  }

  @Test
  @DisplayName("a link to the web is a link; anything else keeps its address beside it")
  void shouldOnlyLinkWhatTelegramAccepts() {
    final var web = render("see [the docs](https://example.com/a)");
    assertThat(web.entities())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.type()).isEqualTo("text_link");
              assertThat(e.url()).isEqualTo("https://example.com/a");
            });

    // Telegram refuses a text_link to a relative path, and the refusal costs the whole message.
    final var local = render("see [the file](./report.md)");
    assertThat(local.entities()).isEmpty();
    assertThat(local.text()).isEqualTo("see the file (./report.md)");
  }

  @Test
  @DisplayName("a heading is a bold line and a list keeps its bullets")
  void shouldDegradeWhatTelegramCannotShow() {
    final var text = render("# Title\n\n- one\n- two\n\n1. first\n2. second");

    assertThat(text.text()).isEqualTo("Title\n\n• one\n• two\n\n1. first\n2. second");
    assertThat(text.entities().getFirst().type()).isEqualTo("bold");
  }

  @Test
  @DisplayName("an escaped character and an entity are shown as what they stand for")
  void shouldUnescape() {
    assertThat(render("a \\*literal\\* &amp; more").text()).isEqualTo("a *literal* & more");
  }

  @Test
  @DisplayName("a quote is a blockquote span")
  void shouldQuote() {
    final var text = render("> quoted\n\nplain");

    assertThat(text.entities()).extracting(MessageEntity::type).containsExactly("blockquote");
    assertThat(spanned(text, text.entities().getFirst())).isEqualTo("quoted");
  }

  @Test
  @DisplayName("text outside the basic multilingual plane is counted the way Telegram counts it")
  void shouldCountInUtf16() {
    final var text = render("🎉 **done**");

    assertThat(spanned(text, text.entities().getFirst())).isEqualTo("done");
  }
}
