package me.kezhenxu94.springagent.appweb.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where an uploaded file may land.
 *
 * <p>The name of an upload is chosen by whoever is uploading, so it is the one piece of a request
 * that is entirely under an attacker's control. Getting this wrong writes into the agent's skills
 * directory, or another user's home, or anywhere on the filesystem the process can reach.
 */
class UploadPathTest {

  /** A home rooted at a temporary directory, which is all {@code artifactPath} needs. */
  private static HomeDir homeAt(final Path root) {
    return new HomeDir() {
      @Override
      public Path root() {
        return root;
      }

      @Override
      public Path folder(final Folder folder) throws IOException {
        return Files.createDirectories(root.resolve(folder.dirName()));
      }

      @Override
      public List<Path> roots() {
        return List.of(root);
      }

      @Override
      public List<Path> dirs(final Folder folder) {
        return List.of(root.resolve(folder.dirName()));
      }

      @Override
      public boolean contains(final Path candidate) {
        return candidate.toAbsolutePath().normalize().startsWith(root);
      }
    };
  }

  @Test
  @DisplayName("an ordinary name lands in the caller's artifacts directory")
  void anOrdinaryNameLandsInArtifacts(@TempDir final Path root) throws Exception {
    final var home = homeAt(root);
    assertThat(FileController.artifactPath("report.pdf", home))
        .isEqualTo(root.resolve("artifacts").resolve("report.pdf"));
  }

  @Test
  @DisplayName("a name that tries to climb out is reduced to its last segment")
  void traversalIsReducedToABasename(@TempDir final Path root) throws Exception {
    final var artifacts = root.resolve("artifacts");
    // Every one of these is a real attempt somebody makes. None may end up outside artifacts, and
    // none may reach the skills directory, which is code the agent will later run.
    for (final var attempt :
        List.of(
            "../../../etc/passwd",
            "../skills/evil.md",
            "..\\..\\windows\\system32\\evil.dll",
            "/etc/passwd",
            "subdir/../../escape.txt")) {
      final var resolved = FileController.artifactPath(attempt, homeAt(root));
      assertThat(resolved).as("%s", attempt).startsWithRaw(artifacts);
      assertThat(resolved.getParent()).as("%s", attempt).isEqualTo(artifacts);
    }
  }

  @Test
  @DisplayName("a name that reduces to nothing usable is refused rather than guessed at")
  void unusableNamesAreRefused(@TempDir final Path root) {
    final var home = homeAt(root);
    for (final var attempt : List.of("", "   ", ".", "..", "/", "../")) {
      assertThatThrownBy(() -> FileController.artifactPath(attempt, home))
          .as("%s", attempt)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> FileController.artifactPath(null, home))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
