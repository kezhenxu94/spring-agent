package me.kezhenxu94.springagent.integration.slack;

import com.google.common.base.Strings;
import com.slack.api.model.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches what a Slack message carried, into the workspace of whoever sent it.
 *
 * <p><b>A Slack file download is not a plain GET.</b> {@code url_private_download} is served only
 * to a request bearing the bot token, and a request without it is answered with {@code 200} and a
 * sign-in page rather than with an error — so a missing or wrong token arrives on disk as an HTML
 * document with the right filename, and is noticed when a model is asked what the spreadsheet says.
 * The content-type check below is what turns that into a refusal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackFiles {

  private final SlackProperties properties;
  private final UserWorkspaceFactory userWorkspaceFactory;
  private final RestClient.Builder restClients;

  /**
   * Saves {@code file} into {@code ownerId}'s artifacts and returns where it landed, or null if it
   * could not be fetched.
   */
  public String download(final File file, final String ownerId) throws java.io.IOException {
    final var url =
        Strings.isNullOrEmpty(file.getUrlPrivateDownload())
            ? file.getUrlPrivate()
            : file.getUrlPrivateDownload();
    if (Strings.isNullOrEmpty(url)) {
      log.debug("Slack file {} carries no download url", file.getId());
      return null;
    }
    final var response =
        restClients
            .build()
            .get()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.botToken())
            .retrieve()
            .toEntity(byte[].class);
    final var body = response.getBody();
    if (body == null || body.length == 0) {
      log.warn("Slack file {} came back empty", file.getId());
      return null;
    }
    // A sign-in page rather than the file: Slack answers an unauthenticated download with one, at
    // 200, so the status code says nothing. Refused rather than saved, because an HTML login form
    // sitting on disk under the name of a spreadsheet is a far worse failure than none at all —
    // nothing downstream would question it, and a model would try to read it.
    final var contentType = response.getHeaders().getContentType();
    if (MediaType.TEXT_HTML.isCompatibleWith(contentType) && !isHtmlFile(file)) {
      log.warn(
          "Slack answered the download of {} with an HTML page, which means the bot token was not"
              + " accepted. Check app.slack.bot-token and that the app has files:read.",
          file.getId());
      return null;
    }

    final var destination = artifactPath(ownerId, file);
    try {
      Files.createDirectories(destination.getParent());
      Files.write(destination, body);
    } catch (Exception e) {
      log.warn("Could not save Slack file {} to {}", file.getId(), destination, e);
      return null;
    }
    log.info("Saved Slack file {} to {} ({} bytes)", file.getId(), destination, body.length);
    return destination.toString();
  }

  /**
   * A local file the agent may upload, or a refusal.
   *
   * <p>The path comes from the model, so it is resolved against the asker's own workspace and
   * asserted to still be inside it. Without that, "upload /etc/passwd" is a working instruction.
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

  private static boolean isHtmlFile(final File file) {
    return MediaType.TEXT_HTML_VALUE.equals(file.getMimetype());
  }

  /**
   * Where a file may be written, which is inside the owner's artifacts directory and nowhere else.
   *
   * <p>The name comes from Slack and is therefore whatever the person who uploaded it chose, so it
   * is reduced to a basename before being joined and the result is asserted to still be under the
   * directory it was resolved against. Prefixed with the file's own id, which keeps two people
   * sharing {@code report.pdf} in the same conversation from overwriting one another.
   */
  Path artifactPath(final String ownerId, final File file) throws java.io.IOException {
    final var artifacts = userWorkspaceFactory.forOwner(ownerId).artifacts().normalize();
    final var name = Strings.isNullOrEmpty(file.getName()) ? file.getId() : file.getName();
    final var basename = Paths.get(name).getFileName().toString();
    final var resolved = artifacts.resolve(file.getId() + "-" + basename).normalize();
    if (!resolved.startsWith(artifacts)) {
      throw new IllegalArgumentException(
          "Slack file " + file.getId() + " named a path outside the artifacts directory");
    }
    return resolved;
  }
}
