package me.kezhenxu94.springagent.integration.telegram.format;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.MessageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That cutting a reply into messages loses nothing and leaves no span hanging off a page. */
class TelegramTextTest {

  private static void assertWellFormed(final TelegramText page) {
    for (final var entity : page.entities()) {
      assertThat(entity.length()).isPositive();
      assertThat(entity.offset()).isNotNegative();
      assertThat(entity.end()).isLessThanOrEqualTo(page.length());
    }
  }

  @Test
  @DisplayName("a reply under the limit is one page")
  void shouldKeepAShortReplyWhole() {
    assertThat(TelegramText.plain("hello").pages(100)).containsExactly(TelegramText.plain("hello"));
  }

  @Test
  @DisplayName("a long reply is cut at a blank line, and every page fits")
  void shouldCutAtParagraphs() {
    final var paragraph = "x".repeat(60);
    final var text = TelegramText.plain(paragraph + "\n\n" + paragraph + "\n\n" + paragraph);

    final var pages = text.pages(130);

    assertThat(pages).hasSize(2);
    assertThat(pages).allSatisfy(page -> assertThat(page.length()).isLessThanOrEqualTo(130));
    assertThat(String.join("\n\n", pages.stream().map(TelegramText::text).toList()))
        .isEqualTo(text.text());
  }

  @Test
  @DisplayName("a span crossing a cut is carried into both pages")
  void shouldSplitSpansAcrossPages() {
    final var text =
        TelegramText.builder().styled("pre", "a".repeat(50) + "\n" + "b".repeat(50)).build();

    final var pages = text.pages(60);

    assertThat(pages).hasSize(2);
    pages.forEach(TelegramTextTest::assertWellFormed);
    assertThat(pages)
        .allSatisfy(
            page ->
                assertThat(page.entities()).extracting(MessageEntity::type).containsExactly("pre"));
  }

  @Test
  @DisplayName("a line longer than a page is cut, never between the halves of an emoji")
  void shouldNotSplitASurrogatePair() {
    final var text = TelegramText.plain("a" + "🎉".repeat(10));

    for (final var page : text.pages(4)) {
      assertThat(Character.isLowSurrogate(page.text().charAt(0))).isFalse();
      assertThat(Character.isHighSurrogate(page.text().charAt(page.length() - 1))).isFalse();
    }
  }

  @Test
  @DisplayName("trimming moves the spans with the text")
  void shouldTrimWithItsSpans() {
    final var text = TelegramText.builder().text("\n\n").styled("bold", "hi").text("  ").build();

    final var trimmed = text.trimmed();

    assertThat(trimmed.text()).isEqualTo("hi");
    assertThat(trimmed.entities()).singleElement().extracting(MessageEntity::offset).isEqualTo(0);
  }

  @Test
  @DisplayName("an enclosing span is listed before the spans inside it")
  void shouldListOuterSpansFirst() {
    final var text =
        TelegramText.builder().styled("blockquote", b -> b.text("a ").styled("bold", "b")).build();

    assertThat(text.entities())
        .extracting(MessageEntity::type)
        .containsExactly("blockquote", "bold");
  }
}
