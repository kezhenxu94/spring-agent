package me.kezhenxu94.springagent.integration.slack.handler;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.DividerBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.composition.TextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import java.util.ArrayList;
import java.util.List;

/**
 * The Block Kit shapes this module builds, and the two limits that shape them.
 *
 * <p>A {@code section}'s text may be 3000 characters and a message may hold 50 blocks. Neither is
 * something a run can be asked to stay within, so long text is split across sections here and the
 * message rolls over when the blocks run out — see {@link SlackMessage}. Splitting on a paragraph
 * and then on a line, rather than at 3000 characters flat, is what keeps a code fence from being
 * cut in half.
 */
public final class SlackBlockKit {

  /** Slack's own ceiling on a section's text. */
  public static final int MAX_SECTION_TEXT = 3000;

  /** Slack's own ceiling on a context element's text. */
  public static final int MAX_CONTEXT_TEXT = 3000;

  private SlackBlockKit() {}

  public static LayoutBlock divider() {
    return DividerBlock.builder().build();
  }

  public static LayoutBlock markdown(final String text) {
    return SectionBlock.builder().text(mrkdwn(text)).build();
  }

  public static TextObject mrkdwn(final String text) {
    return MarkdownTextObject.builder().text(clamp(text, MAX_SECTION_TEXT)).build();
  }

  /** A bold title line. The one Block Kit element that is genuinely a heading. */
  public static LayoutBlock header(final String text) {
    return com.slack.api.model.block.HeaderBlock.builder()
        .text(PlainTextObject.builder().text(clamp(text, 150)).emoji(true).build())
        .build();
  }

  public static LayoutBlock context(final String text) {
    return ContextBlock.builder()
        .elements(List.of(MarkdownTextObject.builder().text(clamp(text, MAX_CONTEXT_TEXT)).build()))
        .build();
  }

  public static LayoutBlock actions(final String blockId, final List<BlockElement> elements) {
    return ActionsBlock.builder().blockId(blockId).elements(elements).build();
  }

  public static ButtonElement button(
      final String actionId, final String text, final String value, final String style) {
    final var builder =
        ButtonElement.builder()
            .actionId(actionId)
            .text(PlainTextObject.builder().text(text).emoji(true).build())
            .value(value);
    if (style != null) {
      builder.style(style);
    }
    return builder.build();
  }

  /**
   * {@code text} as as many sections as it needs.
   *
   * <p>Split on a blank line first and on a newline second, so a paragraph or a fenced block stays
   * whole where it can. A single line longer than the limit is cut, because at that point there is
   * nothing left to split on and showing most of it beats showing none.
   */
  public static List<LayoutBlock> paragraphs(final String text) {
    final var blocks = new ArrayList<LayoutBlock>();
    for (final var chunk : split(text, MAX_SECTION_TEXT)) {
      blocks.add(markdown(chunk));
    }
    return blocks;
  }

  public static List<String> split(final String text, final int limit) {
    final var out = new ArrayList<String>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    if (text.length() <= limit) {
      out.add(text);
      return out;
    }
    var current = new StringBuilder();
    for (final var paragraph : text.split("\n\n", -1)) {
      final var piece = current.isEmpty() ? paragraph : "\n\n" + paragraph;
      if (current.length() + piece.length() <= limit) {
        current.append(piece);
        continue;
      }
      if (!current.isEmpty()) {
        out.add(current.toString());
        current = new StringBuilder();
      }
      if (paragraph.length() <= limit) {
        current.append(paragraph);
        continue;
      }
      for (final var line : paragraph.split("\n", -1)) {
        final var withBreak = current.isEmpty() ? line : "\n" + line;
        if (current.length() + withBreak.length() <= limit) {
          current.append(withBreak);
          continue;
        }
        if (!current.isEmpty()) {
          out.add(current.toString());
          current = new StringBuilder();
        }
        var rest = line;
        while (rest.length() > limit) {
          out.add(rest.substring(0, limit));
          rest = rest.substring(limit);
        }
        current.append(rest);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString());
    }
    return out;
  }

  public static String clamp(final String text, final int limit) {
    if (text == null || text.isEmpty()) {
      // Slack refuses an empty text object outright, which would cost the whole write rather than
      // the one block — so an empty string becomes a space.
      return " ";
    }
    return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
  }

  /**
   * {@code text} as a Slack block quote: every line prefixed, blank ones included, or a quote
   * written in paragraphs comes out as several quotes with prose between them.
   */
  public static String blockquote(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    final var out = new StringBuilder();
    for (final var line : text.split("\n", -1)) {
      if (!out.isEmpty()) {
        out.append("\n");
      }
      out.append("> ").append(line);
    }
    return out.toString();
  }
}
