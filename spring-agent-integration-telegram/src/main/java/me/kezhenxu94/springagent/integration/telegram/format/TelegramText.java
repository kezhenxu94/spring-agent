package me.kezhenxu94.springagent.integration.telegram.format;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.MessageEntity;

/**
 * Text the way Telegram takes it: the characters, and the spans of them that are formatted.
 *
 * <p><b>Entities rather than {@code parse_mode}, and that is what makes streaming safe.</b> With
 * HTML or MarkdownV2 Telegram parses the text itself and refuses the whole message over one tag it
 * cannot close — and a message streamed as it is written is, at almost every moment, a message with
 * something unclosed in it. An entity is an offset and a length, so there is nothing to parse and
 * nothing that can fail to; a span that is cut short is still a span. It is also why nothing here
 * escapes anything: no character means anything but itself.
 *
 * <p>Offsets count UTF-16 code units, exactly as a Java string does, so a span is a {@code
 * substring} away and nothing has to be converted.
 */
public record TelegramText(String text, List<MessageEntity> entities) {

  public TelegramText {
    text = text == null ? "" : text;
    entities = entities == null ? List.of() : List.copyOf(entities);
  }

  public static TelegramText plain(final String text) {
    return new TelegramText(text, List.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public int length() {
    return text.length();
  }

  public boolean isBlank() {
    return text.isBlank();
  }

  /**
   * The part of this between {@code start} and {@code end}, with every span cut to fit and moved to
   * where it now starts. A span that falls outside entirely is dropped, since Telegram refuses one
   * of no length.
   */
  public TelegramText slice(final int start, final int end) {
    final var from = Math.max(0, start);
    final var to = Math.min(text.length(), end);
    if (from >= to) {
      return plain("");
    }
    final var kept = new ArrayList<MessageEntity>();
    for (final var entity : entities) {
      final var s = Math.max(entity.offset(), from);
      final var e = Math.min(entity.end(), to);
      if (e > s) {
        kept.add(entity.resized(s - from, e - s));
      }
    }
    return new TelegramText(text.substring(from, to), kept);
  }

  /**
   * Without leading and trailing whitespace. Telegram trims a message itself, and a span past the
   * end of what is left is refused — so the text is trimmed here, where the spans move with it.
   */
  public TelegramText trimmed() {
    var start = 0;
    var end = text.length();
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    return start == 0 && end == text.length() ? this : slice(start, end);
  }

  /**
   * At most {@code limit} characters, keeping the end rather than the start and saying so. For text
   * that is read as it grows — what the model is thinking — where the newest part is the part worth
   * seeing.
   */
  public TelegramText tail(final int limit) {
    if (text.length() <= limit) {
      return this;
    }
    var start = text.length() - limit + 1;
    if (Character.isLowSurrogate(text.charAt(start))) {
      start++;
    }
    return builder().text("…").append(slice(start, text.length())).build();
  }

  /** At most {@code limit} characters, keeping the start and saying so. */
  public TelegramText head(final int limit) {
    if (text.length() <= limit) {
      return this;
    }
    var end = limit - 1;
    if (Character.isLowSurrogate(text.charAt(end))) {
      end--;
    }
    return builder().append(slice(0, end)).text("…").build();
  }

  /**
   * This, split into pieces of at most {@code limit} characters each, cutting at a blank line where
   * there is one, at a line where there is not, and anywhere only where a single line is longer
   * than a whole piece. Spans that cross a cut are carried into both halves, so a code block split
   * in two is two code blocks rather than one formatted and one not.
   */
  public List<TelegramText> pages(final int limit) {
    final var pages = new ArrayList<TelegramText>();
    var start = 0;
    while (start < text.length()) {
      if (text.length() - start <= limit) {
        pages.add(slice(start, text.length()).trimmed());
        break;
      }
      var cut = text.lastIndexOf("\n\n", start + limit);
      if (cut <= start) {
        cut = text.lastIndexOf('\n', start + limit);
      }
      if (cut <= start) {
        cut = start + limit;
        if (Character.isLowSurrogate(text.charAt(cut))) {
          cut--;
        }
      }
      final var page = slice(start, cut).trimmed();
      if (!page.isBlank()) {
        pages.add(page);
      }
      start = cut;
      while (start < text.length() && text.charAt(start) == '\n') {
        start++;
      }
    }
    if (pages.isEmpty()) {
      pages.add(plain(""));
    }
    return pages;
  }

  /** Builds one up a piece at a time, with each formatted span around what was added inside it. */
  public static final class Builder {

    private final StringBuilder text = new StringBuilder();
    private final List<MessageEntity> entities = new ArrayList<>();

    public Builder text(final String value) {
      if (value != null) {
        text.append(value);
      }
      return this;
    }

    public Builder append(final TelegramText other) {
      if (other == null) {
        return this;
      }
      final var at = text.length();
      text.append(other.text());
      other.entities().forEach(entity -> entities.add(entity.shifted(at)));
      return this;
    }

    /** {@code body}'s output, formatted as {@code type} — {@code bold}, {@code code}, … */
    public Builder styled(final String type, final Consumer<Builder> body) {
      return span(MessageEntity.of(type, 0, 0), body);
    }

    public Builder styled(final String type, final String value) {
      return styled(type, b -> b.text(value));
    }

    /** A link, whose text is {@code body}'s output. */
    public Builder link(final String url, final Consumer<Builder> body) {
      return span(new MessageEntity("text_link", 0, 0, url, null, null), body);
    }

    /** A code block, in {@code language} where one is named. */
    public Builder pre(final String language, final String value) {
      return span(new MessageEntity("pre", 0, 0, null, null, language), b -> b.text(value));
    }

    private Builder span(final MessageEntity template, final Consumer<Builder> body) {
      final var start = text.length();
      final var index = entities.size();
      body.accept(this);
      final var length = text.length() - start;
      if (length > 0) {
        // Inserted ahead of whatever the body added, so that an enclosing span is listed before
        // the spans inside it, which is the order Telegram itself reports them in.
        entities.add(index, template.resized(start, length));
      }
      return this;
    }

    public int length() {
      return text.length();
    }

    public boolean endsWith(final String suffix) {
      return text.length() >= suffix.length()
          && text.substring(text.length() - suffix.length()).equals(suffix);
    }

    public boolean isEmpty() {
      return text.isEmpty();
    }

    public TelegramText build() {
      return new TelegramText(text.toString(), entities);
    }
  }
}
