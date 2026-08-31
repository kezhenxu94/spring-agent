package me.kezhenxu94.springagent.integration.slack.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** That long text is split where Slack's limits force it, and where a reader would want it. */
class SlackBlockKitTest {

  @Test
  @DisplayName("text within the limit is one piece")
  void shouldNotSplitShortText() {
    assertThat(SlackBlockKit.split("hello", 3000)).containsExactly("hello");
  }

  @Test
  @DisplayName("a paragraph boundary is preferred to a hard cut")
  void shouldSplitOnParagraphs() {
    final var first = "a".repeat(60);
    final var second = "b".repeat(60);

    assertThat(SlackBlockKit.split(first + "\n\n" + second, 100)).containsExactly(first, second);
  }

  @Test
  @DisplayName("a paragraph too long for a block is split on its lines instead")
  void shouldSplitOnLines() {
    final var line = "x".repeat(60);

    assertThat(SlackBlockKit.split(line + "\n" + line, 100)).containsExactly(line, line);
  }

  @Test
  @DisplayName("a single line with nothing to split on is cut rather than dropped")
  void shouldCutAnUnsplittableLine() {
    final var pieces = SlackBlockKit.split("y".repeat(250), 100);

    assertThat(pieces).hasSize(3);
    assertThat(String.join("", pieces)).hasSize(250);
  }

  @Test
  @DisplayName("every piece stays within the limit, whatever the shape of the text")
  void shouldNeverExceedTheLimit() {
    final var text = ("word ".repeat(50) + "\n\n" + "z".repeat(400) + "\n").repeat(5);

    assertThat(SlackBlockKit.split(text, 120))
        .allSatisfy(piece -> assertThat(piece).hasSizeLessThanOrEqualTo(120));
  }

  @Test
  @DisplayName("empty text becomes a space, because Slack refuses an empty text object")
  void shouldNeverProduceAnEmptyTextObject() {
    assertThat(SlackBlockKit.clamp("", 10)).isEqualTo(" ");
    assertThat(SlackBlockKit.clamp(null, 10)).isEqualTo(" ");
  }

  @Test
  @DisplayName("every line of a quote is prefixed, blank ones included")
  void shouldPrefixEveryQuotedLine() {
    assertThat(SlackBlockKit.blockquote("one\n\ntwo")).isEqualTo("> one\n> \n> two");
  }
}
