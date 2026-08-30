package me.kezhenxu94.springagent.integration.email;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;

/**
 * What a message says, as text a person or a model can read.
 *
 * <p>Everything here is about reducing what arrived to something that says the same thing when read
 * as it does when rendered. A mail body is not a document — it is a multipart tree with two
 * renderings of the same content, markup that can hide half of itself, and characters that occupy
 * no space. None of that is malicious on its own, and all of it is how text is smuggled past a
 * reader who is shown one thing and a model that is shown another.
 *
 * <p>This is the second line rather than the first. What actually keeps hostile mail out is that
 * the sender's address was authenticated and named in {@code trusted-actors}; a colleague is not
 * hiding instructions in white-on-white text. It is here because it is cheap, because a trusted
 * sender's account can be taken over, and because a mailing list forwards what somebody else wrote.
 */
@Slf4j
public final class MessageText {

  /**
   * HTML read by a real parser rather than by pattern-matching over the markup.
   *
   * <p>{@code allElements} is what makes this the right tool for a mail body: it returns {@code
   * body().text()}, the whole readable content in document order, rather than the per-selector
   * chunks the reader produces for indexing. Three things that would each be a hand-written rule
   * fall out of the parse instead of being rules — script and style contents are {@code DataNode}s
   * and comments are {@code Comment}s, none of which {@code text()} collects, and entities are
   * decoded by the parser that understands them. Taking {@code body()} also drops the head, so a
   * title and a stylesheet do not arrive as prose.
   *
   * <p>What it costs is paragraph structure: {@code text()} normalises whitespace, so a mail laid
   * out in a table arrives as one long line. Worth it. The alternative was several regular
   * expressions over untrusted markup, and a regular expression that believes it understands HTML
   * is wrong in ways whose examples are all somebody else's incident report.
   */
  private static final JsoupDocumentReaderConfig HTML =
      JsoupDocumentReaderConfig.builder().allElements(true).charset("UTF-8").build();

  /**
   * Characters that render as nothing and so can carry text a reader will never see.
   *
   * <p>The one thing the parser does not do, because from HTML's point of view these are ordinary
   * content. The bidirectional overrides and isolates first, which reorder what follows them and
   * can make a line read backwards from how it is stored. Then the zero-width space and the
   * byte-order mark, which pad a word into something a reader skims past. Then the Unicode tag
   * block, whose only modern use is smuggling: it encodes ASCII invisibly, and is the standard way
   * of hiding an instruction inside an innocuous sentence.
   *
   * <p>Deliberately not every format character. The zero-width joiner and non-joiner at U+200C and
   * U+200D are left alone: they are how Arabic and several Indic scripts are written correctly, and
   * dropping them would corrupt ordinary text from ordinary people in order to close a channel that
   * carries no payload on its own.
   */
  private static final Pattern INVISIBLE =
      Pattern.compile(
          "[\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]|[\\x{E0000}-\\x{E007F}]");

  private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");
  private static final Pattern TRAILING_SPACE = Pattern.compile("[ \t]+\n");

  private MessageText() {}

  /**
   * The readable body of {@code part}, at most {@code limit} characters.
   *
   * <p>Never throws. A body that cannot be read is an empty string, because a message whose text is
   * unreadable is still a message that arrived, and the alternative — letting it escape — would put
   * an attachment nobody can parse in the way of every message behind it.
   */
  public static String bodyOf(final Part part, final int limit) {
    try {
      return truncate(clean(extract(part)), limit);
    } catch (Exception e) {
      log.debug("Could not read a message body", e);
      return "";
    }
  }

  /** Whitespace-collapsed and stripped of anything invisible. */
  public static String clean(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    final var visible = INVISIBLE.matcher(text).replaceAll("");
    final var normalised = visible.replace("\r\n", "\n").replace('\r', '\n');
    return BLANK_LINES
        .matcher(TRAILING_SPACE.matcher(normalised).replaceAll("\n"))
        .replaceAll("\n\n")
        .strip();
  }

  public static String truncate(final String text, final int limit) {
    if (text.length() <= limit) {
      return text;
    }
    // Said rather than silently done. A body that stops mid-sentence with no explanation reads, to
    // whoever is assessing it, like a message that stopped mid-sentence.
    return text.substring(0, limit) + "\n[...truncated]";
  }

  /**
   * The text of a part, preferring {@code text/plain} over the HTML beside it.
   *
   * <p>Depth-first through {@code multipart/*}, taking the first plain part anywhere in the tree
   * and falling back to HTML only if there was none. That ordering is the point: a {@code
   * multipart/alternative} carries the same message twice, the two halves are written by the sender
   * and need not agree, and the plain one is both the one that needs no parsing and the one nothing
   * can be hidden in.
   *
   * <p>Attachments are not read. Not a limitation to lift later — a run that parsed whatever was
   * attached would be running a parser of somebody's choosing over bytes of their choosing, which
   * is a much larger thing to have accepted than the mail itself.
   */
  private static String extract(final Part part) throws Exception {
    final var plain = firstOfType(part, "text/plain");
    if (plain != null) {
      return plain;
    }
    final var html = firstOfType(part, "text/html");
    return html == null ? "" : fromHtml(html);
  }

  private static String firstOfType(final Part part, final String mimeType) throws Exception {
    if (part.isMimeType(mimeType) && !isAttachment(part)) {
      final var content = part.getContent();
      return content instanceof String text ? text : null;
    }
    if (part.getContent() instanceof Multipart multipart) {
      for (var i = 0; i < multipart.getCount(); i++) {
        final var found = firstOfType(multipart.getBodyPart(i), mimeType);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static boolean isAttachment(final Part part) throws Exception {
    final var disposition = part.getDisposition();
    return disposition != null && Part.ATTACHMENT.equalsIgnoreCase(disposition);
  }

  /**
   * HTML as the text it renders to.
   *
   * <p>The honest limit of this, worth naming rather than pretending away: text hidden by CSS —
   * white on white, {@code display:none} — is text the parser returns and a person reading the mail
   * never saw. Making the two agree needs a rendering engine, not a parser. It is a difference that
   * only matters for a sender the deployment already decided to trust.
   */
  private static String fromHtml(final String html) {
    final var resource = new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8));
    return new JsoupDocumentReader(resource, HTML)
        .get().stream().map(document -> document.getText()).findFirst().orElse("");
  }
}
