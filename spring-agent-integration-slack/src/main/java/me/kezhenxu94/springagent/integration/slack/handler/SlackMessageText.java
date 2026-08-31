package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.model.File;
import com.slack.api.model.event.MessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.slack.SlackFiles;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.stereotype.Component;

/**
 * What a message says, as the model should read it.
 *
 * <p>Split from {@code SlackMessageReceiveHandler} because of when it runs rather than what it
 * does: everything here may take seconds — a file is downloaded, an image is written to disk — and
 * none of it may happen on the thread Slack is waiting for an acknowledgement on. It is called from
 * inside the supplier that handler hands to {@code SpringAgent}, on the run's own thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackMessageText {

  private final SlackUserNames userNames;
  private final SlackFiles files;
  private final SlackMessages messages;

  /** The message as the model should read it: what was said, and what came with it. */
  public String of(final MessageEvent event, final String userId) {
    final var text = new StringBuilder(userNames.resolve(event.getText()));
    if (event.getFiles() == null || event.getFiles().isEmpty()) {
      return text.toString();
    }
    for (final var file : event.getFiles()) {
      try {
        append(text, file, userId);
      } catch (Exception e) {
        // One file that cannot be fetched costs its own line, not the message. What the person
        // typed is usually most of what they meant, and losing it to a failed download would be a
        // worse trade than answering without the attachment.
        log.warn("Could not read Slack file {} shared by {}", file.getId(), userId, e);
      }
    }
    return text.toString();
  }

  private void append(final StringBuilder text, final File file, final String userId)
      throws Exception {
    final var saved = files.download(file, userId);
    if (saved == null) {
      return;
    }
    if (isImage(file)) {
      text.append("\nThe image was saved to: ")
          .append(saved)
          .append(". Use the RecognizeImage tool to see what it shows.");
      return;
    }
    if (isAudio(file)) {
      text.append("\nUser sent an audio file, saved at: ")
          .append(saved)
          .append(", use the TranscribeAudio tool to get the text.");
      return;
    }
    text.append("\nUser shared a file: ")
        .append(Strings.nullToEmpty(file.getName()))
        .append(", saved at: ")
        .append(saved);
  }

  /**
   * The message as the reply would show it while it waits to be read, or null where a line cannot
   * show it at all.
   *
   * <p>Nothing here reads anything but the event: what a file says is only known once it has been
   * downloaded, and that happens when the run reads the message, not while the event is still being
   * acknowledged. So a message carrying only a file is shown as the fact that it arrived.
   */
  public String display(final MessageEvent event) {
    final var text = event.getText();
    if (!Strings.isNullOrEmpty(text)) {
      return userNames.resolve(text);
    }
    if (event.getFiles() != null && !event.getFiles().isEmpty()) {
      return messages.get("message-unshown");
    }
    return null;
  }

  private static boolean isImage(final File file) {
    return startsWith(file.getMimetype(), "image/");
  }

  private static boolean isAudio(final File file) {
    return startsWith(file.getMimetype(), "audio/") || startsWith(file.getMimetype(), "video/");
  }

  private static boolean startsWith(final String value, final String prefix) {
    return value != null && value.startsWith(prefix);
  }
}
