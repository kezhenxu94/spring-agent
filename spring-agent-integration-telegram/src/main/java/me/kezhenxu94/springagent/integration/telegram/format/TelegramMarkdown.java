package me.kezhenxu94.springagent.integration.telegram.format;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the model wrote, as Telegram shows it.
 *
 * <p><b>The model writes ordinary markdown and this translates it</b>, rather than the model being
 * asked to write Telegram's own dialect the way the Slack surface asks for mrkdwn. Telegram's two
 * text modes are both parsed by Telegram, and both refuse the whole message over one mistake: HTML
 * over a tag left open, MarkdownV2 over any of eighteen punctuation characters left unescaped —
 * which a model gets wrong in most answers, and which a message streamed as it is written gets
 * wrong at almost every moment regardless. Parsed here instead, into {@link TelegramText}, the
 * answer cannot be refused for its formatting at all: an unclosed {@code **} is two asterisks, and
 * a code fence still being written is a code block that has not ended yet.
 *
 * <p>What Telegram has no way to show becomes the nearest thing it has: a heading is a bold line, a
 * list keeps its bullets as text, an image is a link to it. A table is not translated, because a
 * proportional font cannot line one up — the reply format asks the model not to write one.
 */
public final class TelegramMarkdown {

  private static final Parser PARSER =
      Parser.builder(
              new MutableDataSet()
                  .set(Parser.EXTENSIONS, List.of(StrikethroughExtension.create()))
                  .toImmutable())
          .build();

  /**
   * A language Telegram will accept on a code block. Anything else is left off rather than sent,
   * since the language is free text from the model and a strange one costs the highlighting only.
   */
  private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_+#.-]{1,32}");

  private TelegramMarkdown() {}

  public static TelegramText render(final String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return TelegramText.plain("");
    }
    final var out = TelegramText.builder();
    blocks(PARSER.parse(markdown), out, "", false);
    return out.build().trimmed();
  }

  private static void blocks(
      final Node parent, final TelegramText.Builder out, final String indent, final boolean tight) {
    var first = true;
    for (var node = parent.getFirstChild(); node != null; node = node.getNext()) {
      if (node instanceof Reference) {
        // A link reference definition is where a link points, not something to show.
        continue;
      }
      if (!first) {
        out.text(tight ? "\n" : "\n\n").text(indent);
      }
      block(node, out, indent);
      first = false;
    }
  }

  private static void block(final Node node, final TelegramText.Builder out, final String indent) {
    switch (node) {
      case Paragraph paragraph -> inlines(paragraph, out);
      case Heading heading -> out.styled("bold", b -> inlines(heading, b));
      case BlockQuote quote -> out.styled("blockquote", b -> blocks(quote, b, "", false));
      case FencedCodeBlock fenced -> out.pre(language(fenced), code(fenced.getContentChars()));
      case IndentedCodeBlock indented -> out.pre(null, code(indented.getContentChars()));
      case ListBlock list -> list(list, out, indent);
      case ThematicBreak ignored -> out.text("———");
      default -> {
        if (node.hasChildren()) {
          blocks(node, out, indent, false);
        } else {
          out.text(node.getChars().toString().strip());
        }
      }
    }
  }

  private static void list(
      final ListBlock list, final TelegramText.Builder out, final String indent) {
    var number = list instanceof OrderedList ordered ? ordered.getStartNumber() : 1;
    var first = true;
    for (var item = list.getFirstChild(); item != null; item = item.getNext()) {
      if (!first) {
        out.text("\n").text(indent);
      }
      final var marker = list instanceof BulletList ? "• " : number++ + ". ";
      out.text(marker);
      blocks(item, out, indent + " ".repeat(marker.length()), true);
      first = false;
    }
  }

  private static void inlines(final Node parent, final TelegramText.Builder out) {
    for (var node = parent.getFirstChild(); node != null; node = node.getNext()) {
      inline(node, out);
    }
  }

  private static void inline(final Node node, final TelegramText.Builder out) {
    switch (node) {
      case Text text -> out.text(text.getChars().unescape());
      case Code code -> out.styled("code", code.getText().toString());
      case StrongEmphasis strong -> out.styled("bold", b -> inlines(strong, b));
      case Emphasis emphasis -> out.styled("italic", b -> inlines(emphasis, b));
      case Strikethrough strike -> out.styled("strikethrough", b -> inlines(strike, b));
      case Link link -> link(link.getUrl().unescape(), link, out);
      case Image image -> {
        final var url = image.getUrl().unescape();
        final var alt = image.getText().unescape();
        if (linkable(url)) {
          out.link(url, b -> b.text("🖼 " + (alt.isBlank() ? url : alt)));
        } else {
          out.text(alt.isBlank() ? url : alt);
        }
      }
      // Telegram recognises a bare address on its own, so there is nothing to add to one.
      case AutoLink auto -> out.text(auto.getText().unescape());
      case MailLink mail -> out.text(mail.getText().unescape());
      case SoftLineBreak ignored -> out.text("\n");
      case HardLineBreak ignored -> out.text("\n");
      default -> {
        if (node.hasChildren()) {
          inlines(node, out);
        } else {
          // HTML the model wrote, an entity, anything else: shown as written, which for an entity
          // means decoded — &amp; is an ampersand to the reader.
          out.text(node.getChars().unescape());
        }
      }
    }
  }

  /**
   * A link Telegram will take as one, or its text with the address beside it. A {@code text_link}
   * to anything but http(s) is refused, and the refusal costs the whole message rather than the
   * link — so a relative path or a scheme Telegram does not open is written out instead.
   */
  private static void link(final String url, final Node link, final TelegramText.Builder out) {
    if (linkable(url)) {
      out.link(url, b -> inlines(link, b));
      return;
    }
    inlines(link, out);
    if (!url.isBlank()) {
      out.text(" (" + url + ")");
    }
  }

  private static boolean linkable(final String url) {
    final var lower = url.toLowerCase(Locale.ROOT);
    return (lower.startsWith("https://") || lower.startsWith("http://"))
        && url.chars().noneMatch(Character::isWhitespace);
  }

  private static String language(final FencedCodeBlock fenced) {
    final var info = fenced.getInfo().toString().strip();
    final var word = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
    return LANGUAGE.matcher(word).matches() ? word : null;
  }

  private static String code(final CharSequence content) {
    final var text = content.toString();
    return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
  }
}
