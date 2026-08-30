package me.kezhenxu94.springagent.integration.feishu.tools;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import me.kezhenxu94.springagent.core.tools.HomeDir;

/** Where the Feishu tools put files, and where they take them from when nobody says. */
final class FeishuFiles {

  static final String DEFAULT_FOLDER_TOKEN = "V2wjfOTZFluEQedLAG8csJwwnvg";

  /**
   * Where a file a tool brings back from Feishu is written, under the requester's own artifacts
   * directory.
   *
   * <p>The name is reduced to its last segment before it is resolved, because it is a name the
   * model chose and often a name Feishu chose before that: neither is trusted to stay inside the
   * directory it is being resolved against, and {@code ../../.ssh/authorized_keys} is a filename as
   * far as either is concerned. The check afterwards is the one that actually holds — a basename
   * cannot escape, so it stands as an assertion that the reduction did what it claims.
   */
  static Path artifactPath(final String fileName, final HomeDir home) throws IOException {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName is required");
    }
    final String basename;
    try {
      final var nameOnly = Path.of(fileName).getFileName();
      basename = nameOnly == null ? null : nameOnly.toString();
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("fileName is invalid: " + fileName, e);
    }
    if (basename == null || basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
      throw new IllegalArgumentException("fileName is invalid: " + fileName);
    }
    final var artifacts = home.artifacts().normalize();
    final var dest = artifacts.resolve(basename).normalize();
    if (!dest.startsWith(artifacts)) {
      throw new IllegalArgumentException("fileName escapes artifacts directory: " + fileName);
    }
    return dest;
  }

  private FeishuFiles() {}
}
