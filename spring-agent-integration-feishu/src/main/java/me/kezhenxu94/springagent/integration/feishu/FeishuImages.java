package me.kezhenxu94.springagent.integration.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.stereotype.Component;

/**
 * Turns the images a message names into keys Feishu will show, since a card, a rich-text post and
 * an image message can only display what the tenant has uploaded.
 *
 * <p>For a message the model composes with a tool, only a file in the asker's own workspace is
 * uploaded — the containment check every tool reading a local path applies — and a URL is refused
 * rather than fetched: fetching an address the model wrote would make the server a proxy to
 * whatever it can reach. The streaming reply card fetches URLs through {@code FeishuCard} and keeps
 * doing so; this does not extend that to anything new.
 */
@Component
@RequiredArgsConstructor
public class FeishuImages {

  /** A markdown image, capturing its alt text and its target. */
  public static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[(.*?)\\]\\(([^)\\s]+)\\)");

  private static final String FILE_SCHEME = "file:";

  private final Client feishu;
  private final FeishuMessages messages;

  /**
   * The key to put where {@code source} was: a local file inside {@code home} is uploaded, anything
   * that is neither a path nor a URL — a key already — is returned as it is, for Feishu to judge.
   *
   * @throws IllegalArgumentException naming why the image cannot be used, for the model to read
   */
  public String keyFor(final String source, final HomeDir home) {
    if (isRemote(source)) {
      throw new IllegalArgumentException(messages.get("tool-image-remote-refused", source));
    }
    if (!isLocal(source)) {
      return source;
    }
    final var path = pathOf(source);
    if (!home.contains(path) || !Files.isRegularFile(path)) {
      throw new IllegalArgumentException(messages.get("tool-image-not-in-workspace", source));
    }
    return upload(feishu, path.toFile());
  }

  /** {@code markdown} with every image whose target is a path or a URL pointed at its key. */
  public String inMarkdown(final String markdown, final HomeDir home) {
    if (markdown == null) {
      return null;
    }
    final var matcher = MARKDOWN_IMAGE.matcher(markdown);
    final var out = new StringBuilder();
    while (matcher.find()) {
      final var replacement = "![" + matcher.group(1) + "](" + keyFor(matcher.group(2), home) + ")";
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /**
   * Uploads {@code file} as a message image and returns its key.
   *
   * @throws IllegalStateException carrying what Feishu said, when it refuses
   */
  @SneakyThrows
  public static String upload(final Client feishu, final File file) {
    final var response =
        feishu
            .im()
            .v1()
            .image()
            .create(
                CreateImageReq.newBuilder()
                    .createImageReqBody(
                        CreateImageReqBody.newBuilder().imageType("message").image(file).build())
                    .build());
    if (!response.success()) {
      throw new IllegalStateException(
          "Failed to upload image " + file.getName() + ": " + FeishuApiErrors.describe(response));
    }
    return response.getData().getImageKey();
  }

  public static boolean isRemote(final String source) {
    return source.startsWith("http://") || source.startsWith("https://");
  }

  /** A {@code file://} URL, as {@code GenerateImage} returns, or a plain absolute path. */
  public static boolean isLocal(final String source) {
    return source.startsWith(FILE_SCHEME) || source.startsWith("/");
  }

  public static Path pathOf(final String source) {
    return (source.startsWith(FILE_SCHEME) ? Path.of(URI.create(source)) : Path.of(source))
        .toAbsolutePath()
        .normalize();
  }
}
