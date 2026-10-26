package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.telegram.TelegramFiles;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Attachment;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import org.springframework.stereotype.Component;

/**
 * What a message says, as the model should read it.
 *
 * <p>Apart from the receive handler for the reason {@code SlackMessageText} is: a file is
 * downloaded here, which takes seconds, and this runs inside the supplier the run calls on its own
 * thread rather than on the thread taking updates off the poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageText {

  /** How much of a message being replied to is carried along with the reply. */
  private static final int QUOTED = 1000;

  private final TelegramFiles files;
  private final TelegramMessages messages;

  /** What was sent: the text or caption, what it replies to, and what it carries. */
  public String of(final Message message, final String userId) {
    final var text = new StringBuilder();
    final var quoted = quoted(message);
    if (quoted != null) {
      text.append(quoted).append("\n\n");
    }
    text.append(Strings.nullToEmpty(message.textOrCaption()));
    for (final var carried : attachments(message)) {
      try {
        final var saved =
            files.download(
                carried.attachment(),
                String.valueOf(message.messageId()),
                carried.fallbackName(),
                userId);
        text.append(carried.kind().note(saved, carried.attachment().fileName()));
      } catch (Exception e) {
        // One file that cannot be fetched costs its own line, not the message.
        log.warn("Could not read a Telegram {} sent by {}", carried.kind(), userId, e);
        text.append("\nUser sent a ")
            .append(carried.kind().name().toLowerCase(java.util.Locale.ROOT))
            .append(" that could not be downloaded: ")
            .append(e.getMessage());
      }
    }
    return text.toString();
  }

  /**
   * The message as a queued line shows it, or null where a line cannot. Reads nothing but the
   * update, since this runs while the update is being handled.
   */
  public String display(final Message message) {
    final var text = message.textOrCaption();
    if (!Strings.isNullOrEmpty(text)) {
      return text;
    }
    return attachments(message).isEmpty() ? null : messages.get("message-unshown");
  }

  /**
   * The message being replied to, quoted, where it is somebody else's. Telegram sends it along with
   * the reply, and in a group it is usually what the question is about — "@bot what does this
   * mean?". A reply to the bot's own answer needs nothing: that answer is in the conversation
   * already.
   */
  private static String quoted(final Message message) {
    final var original = message.replyToMessage();
    if (original == null) {
      return null;
    }
    final var text = original.textOrCaption();
    if (Strings.isNullOrEmpty(text)) {
      return null;
    }
    final var author = original.from();
    if (author != null && author.isBot()) {
      return null;
    }
    final var clipped = text.length() <= QUOTED ? text : text.substring(0, QUOTED) + "…";
    return "In reply to "
        + (author == null ? "a message" : author.displayName())
        + ", who wrote:\n> "
        + clipped.replace("\n", "\n> ");
  }

  private record Carried(Kind kind, Attachment attachment, String fallbackName) {}

  private static List<Carried> attachments(final Message message) {
    final var found = new ArrayList<Carried>();
    if (message.photo() != null && !message.photo().isEmpty()) {
      // Every size Telegram made of it, smallest first; the largest is the one worth reading.
      found.add(new Carried(Kind.IMAGE, message.photo().getLast(), "photo.jpg"));
    }
    if (message.document() != null) {
      final var mime = Strings.nullToEmpty(message.document().mimeType());
      final var kind = mime.startsWith("image/") ? Kind.IMAGE : Kind.FILE;
      found.add(new Carried(kind, message.document(), "document"));
    }
    if (message.audio() != null) {
      found.add(new Carried(Kind.AUDIO, message.audio(), "audio.mp3"));
    }
    if (message.voice() != null) {
      found.add(new Carried(Kind.AUDIO, message.voice(), "voice.ogg"));
    }
    if (message.video() != null) {
      found.add(new Carried(Kind.AUDIO, message.video(), "video.mp4"));
    }
    if (message.videoNote() != null) {
      found.add(new Carried(Kind.AUDIO, message.videoNote(), "video-note.mp4"));
    }
    return found;
  }

  /** What a file is to the model, which decides the tool it is pointed at. */
  private enum Kind {
    IMAGE {
      @Override
      String note(final String saved, final String name) {
        return "\nThe image was saved to: "
            + saved
            + ". Use the RecognizeImage tool to see what it shows.";
      }
    },
    // Video too: what the model can do with one is hear it.
    AUDIO {
      @Override
      String note(final String saved, final String name) {
        return "\nUser sent an audio file, saved at: "
            + saved
            + ", use the TranscribeAudio tool to get the text.";
      }
    },
    FILE {
      @Override
      String note(final String saved, final String name) {
        return "\nUser shared a file: " + Strings.nullToEmpty(name) + ", saved at: " + saved;
      }
    };

    abstract String note(String saved, String name);
  }
}
