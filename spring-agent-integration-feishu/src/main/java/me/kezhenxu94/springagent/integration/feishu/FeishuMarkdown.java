package me.kezhenxu94.springagent.integration.feishu;

/**
 * Text somebody else wrote, made safe to put in a Feishu card.
 *
 * <p>A card's markdown is CommonMark plus a handful of Feishu tags (see {@link FeishuReplyFormat}
 * for the whole dialect), and two of those tags do something rather than merely render: {@code
 * <at>} notifies the people it names and {@code <at id=all></at>} notifies an entire group. So a
 * message quoted verbatim from somewhere else is not inert content — it is whatever its author
 * chose to make the chat client do. Somebody typing {@code <at id=all></at>} into the web composer,
 * with the answer being mirrored into a Feishu group, would have this bot ping everybody in it on
 * their say-so.
 *
 * <p>Escaping rather than stripping, so what the person actually wrote is still readable: every
 * character markdown or Feishu would act on becomes the HTML entity that renders as itself, using
 * the same list the model is given in {@link FeishuReplyFormat#COMMON}. That list is the surface's
 * own documented answer to "how do I write this character literally", which is why it is the right
 * one here and not a longer one of our own invention.
 */
final class FeishuMarkdown {

  private FeishuMarkdown() {}

  /** {@code text} with every character Feishu card markdown would act on rendered as itself. */
  static String escaped(final String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    // One pass, character by character, rather than a chain of String::replace. Not a
    // micro-optimisation: a chain cannot be correctly ordered. Every entity written here contains
    // an ampersand and most contain a hash, so a later replacement of either would escape what an
    // earlier one had just produced — chaining these in any order turns "&#60;" into
    // "&amp;&#35;60;" only if the input said it, and into that anyway if it did not.
    final var escaped = new StringBuilder(text.length() + 16);
    for (var i = 0; i < text.length(); i++) {
      final var character = text.charAt(i);
      final var replacement =
          switch (character) {
            // The one that matters: <at id=all></at> notifies an entire group, and <at id=ou_x>
            // notifies a person. Taking the angle brackets takes every Feishu tag with them.
            case '<' -> "&#60;";
            case '>' -> "&#62;";
            // These only change how text looks, but a quoted message that silently loses its
            // asterisks is a message misquoted.
            case '*' -> "&#42;";
            case '_' -> "&#95;";
            case '~' -> "&sim;";
            case '#' -> "&#35;";
            case '`' -> "&#96;";
            // Last in the list and first in effect: an ampersand the author wrote has to survive
            // as one, and it can only do that if it is escaped too. Otherwise text that merely
            // looked like an entity would arrive as the character it names.
            case '&' -> "&amp;";
            default -> null;
          };
      if (replacement == null) {
        escaped.append(character);
      } else {
        escaped.append(replacement);
      }
    }
    return escaped.toString();
  }
}
