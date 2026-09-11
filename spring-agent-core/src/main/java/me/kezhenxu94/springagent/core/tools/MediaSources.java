package me.kezhenxu94.springagent.core.tools;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Turns whatever a model typed as an image into {@link Media} a model provider can be handed.
 *
 * <p>Three spellings reach a tool, and a model uses all three: an absolute local path, a {@code
 * file://} URL, and an {@code http(s)} URL. The first two name a file this machine already holds —
 * which is the common case, because {@code GenerateImage} answers with {@code file://} URLs into
 * the user's own artifacts directory, so "edit the image you just made" is a local file both times.
 * Publishing such a file to a public URL only to fetch it straight back would be a round trip that
 * buys nothing and briefly exposes the user's image to anyone holding the link.
 *
 * <p>So a local source is read here and travels as bytes, and only a remote one is downloaded.
 * Either way the caller gets {@code Media} and never a path, which is what lets a model provider
 * stay ignorant of this project's directory layout.
 *
 * <p><b>A local path is confined to the asking user's own workspace</b>, and that check is the
 * reason this class may be pointed at the filesystem at all. The paths come from a model, which got
 * them from a conversation, so {@code /etc/shadow} is a thing that will eventually be asked for.
 * {@link HomeDir#contains} answers for the user's, the group's and the tenant's roots together, so
 * a shared artifact stays readable while somebody else's does not.
 *
 * <p>Every failure is a {@code null} for that one source, logged with the reason and never thrown:
 * one unreadable reference must not cost the caller the images that did resolve. What a missing one
 * means is the caller's to decide — {@code RecognizeImage} gives up on the whole call, {@code
 * GenerateImage} generates from the rest.
 */
@Slf4j
@RequiredArgsConstructor
public class MediaSources {

  private static final String FILE_SCHEME = "file://";

  private final RestTemplate restTemplate;

  /**
   * Reads one source, or returns null where it could not be read.
   *
   * @param source an absolute local path, a {@code file://} URL, or an {@code http(s)} URL
   * @param userId whose run this is, for the log line that says why a path was refused
   * @param home the workspace a local path has to lie inside
   */
  public Media resolve(final String source, final String userId, final HomeDir home) {
    final var startedAt = System.nanoTime();
    if (source == null || source.isBlank()) {
      log.warn("Ignoring a blank image source for user {}", userId);
      return null;
    }
    try {
      if (source.startsWith("http://") || source.startsWith("https://")) {
        return download(source, userId, startedAt);
      }
      return read(source, localPath(source), userId, home, startedAt);
    } catch (Exception e) {
      log.error(
          "Failed to load image {} for user {} (after {} ms)",
          source,
          userId,
          elapsedMillis(startedAt),
          e);
      return null;
    }
  }

  /**
   * The path a local source names.
   *
   * <p>{@code file://} is parsed as a URI rather than trimmed as a string, so that a
   * percent-encoded name round-trips — a generated file is named after a UUID, but a file the user
   * put there keeps whatever they called it. {@code Path.of} on the raw {@code file:///…} text
   * would produce a relative path beginning with a literal {@code file:} segment, which fails the
   * confinement check below and is then reported as a workspace problem, which is not what went
   * wrong.
   */
  private static Path localPath(final String source) {
    if (source.startsWith(FILE_SCHEME)) {
      return Path.of(URI.create(source)).toAbsolutePath().normalize();
    }
    return Path.of(source).toAbsolutePath().normalize();
  }

  private Media download(final String source, final String userId, final long startedAt) {
    log.debug("Downloading image for user {} from URL: {}", userId, source);
    final var bytes = restTemplate.getForObject(URI.create(source), byte[].class);
    if (bytes == null || bytes.length == 0) {
      log.warn("Nothing to read at image URL {} for user {}", source, userId);
      return null;
    }
    log.info(
        "Downloaded image {} for user {}, size={} bytes, took {} ms",
        source,
        userId,
        bytes.length,
        elapsedMillis(startedAt));
    return Media.builder().name(source).mimeType(MimeTypeUtils.IMAGE_PNG).data(bytes).build();
  }

  private static Media read(
      final String source,
      final Path path,
      final String userId,
      final HomeDir home,
      final long startedAt)
      throws Exception {
    if (!home.contains(path)) {
      log.warn(
          "Rejected image path outside user {} workspace: {} (resolved to {})",
          userId,
          source,
          path);
      return null;
    }
    if (!Files.exists(path)) {
      log.warn("Image path for user {} does not exist: {} (resolved to {})", userId, source, path);
      return null;
    }
    final var mimeType = Files.probeContentType(path);
    if (mimeType == null) {
      log.info("Could not probe mime type of {}, assuming {}", path, MimeTypeUtils.IMAGE_PNG);
    }
    final var bytes = Files.readAllBytes(path);
    log.info(
        "Read local image {} for user {}, mimeType={}, size={} bytes, took {} ms",
        path,
        userId,
        mimeType != null ? mimeType : MimeTypeUtils.IMAGE_PNG_VALUE,
        bytes.length,
        elapsedMillis(startedAt));
    return Media.builder()
        .name(path.getFileName().toString())
        .mimeType(
            mimeType != null ? MimeTypeUtils.parseMimeType(mimeType) : MimeTypeUtils.IMAGE_PNG)
        .data(bytes)
        .build();
  }

  private static long elapsedMillis(final long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
