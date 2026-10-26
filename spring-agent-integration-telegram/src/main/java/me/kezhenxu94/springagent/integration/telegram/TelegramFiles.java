package me.kezhenxu94.springagent.integration.telegram;

import com.google.common.base.Strings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Attachment;
import org.springframework.stereotype.Component;

/**
 * Fetches what a Telegram message carried into the workspace of whoever sent it, and checks a file
 * the model wants to upload is one that asker may hand out.
 *
 * <p>A download is two calls: {@code getFile} says where the bytes are, and they are fetched from
 * there. Telegram's own server serves at most 20 MB that way and refuses the first call over
 * anything bigger — a limit the local Bot API server, at {@code app.telegram.api-url}, lifts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramFiles {

  private final TelegramBotApi telegram;
  private final UserWorkspaceFactory userWorkspaceFactory;

  /**
   * Saves {@code attachment} into {@code ownerId}'s artifacts and returns where it landed.
   *
   * @param prefix what keeps two files of the same name apart — the message they came on
   * @param fallbackName what to call it where Telegram gives no name, which it does not for a
   *     photo, a voice note or a video note
   */
  public String download(
      final Attachment attachment,
      final String prefix,
      final String fallbackName,
      final String ownerId)
      throws java.io.IOException {
    final var file = telegram.getFile(attachment.fileId());
    if (file == null || Strings.isNullOrEmpty(file.filePath())) {
      throw new java.io.IOException("Telegram did not say where the file is");
    }
    final var body = telegram.download(file);
    if (body == null || body.length == 0) {
      throw new java.io.IOException("Telegram sent the file back empty");
    }
    final var name =
        Strings.isNullOrEmpty(attachment.fileName()) ? fallbackName : attachment.fileName();
    final var destination = artifactPath(ownerId, prefix, name);
    Files.createDirectories(destination.getParent());
    Files.write(destination, body);
    log.info("Saved a Telegram file to {} ({} bytes)", destination, body.length);
    return destination.toString();
  }

  /**
   * A local file the agent may upload, or a refusal. The path comes from the model, so it is
   * resolved against the asker's own workspace and has to still be inside it — the same check as
   * {@code SlackFiles#readable}, and without it "upload /etc/passwd" is a working instruction.
   */
  public java.io.File readable(final String path, final String ownerId) {
    if (Strings.isNullOrEmpty(path)) {
      throw new IllegalArgumentException("No path was given");
    }
    final var home = userWorkspaceFactory.forOwner(ownerId);
    final var resolved = Paths.get(path).toAbsolutePath().normalize();
    if (!home.contains(resolved)) {
      throw new IllegalStateException(
          "That file is outside this user's workspace, and the agent will not upload it: " + path);
    }
    final var file = resolved.toFile();
    if (!file.isFile()) {
      throw new IllegalStateException("There is no file at " + path);
    }
    return file;
  }

  /**
   * Inside the owner's artifacts and nowhere else: the name is whatever the sender chose, so it is
   * reduced to a basename and the result asserted to still be under the directory.
   */
  Path artifactPath(final String ownerId, final String prefix, final String name)
      throws java.io.IOException {
    final var artifacts = userWorkspaceFactory.forOwner(ownerId).artifacts().normalize();
    final var basename = Paths.get(name).getFileName().toString();
    final var resolved = artifacts.resolve(prefix + "-" + basename).normalize();
    if (!resolved.startsWith(artifacts)) {
      throw new IllegalArgumentException("A Telegram file named a path outside the artifacts");
    }
    return resolved;
  }
}
