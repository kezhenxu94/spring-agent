package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserHome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeishuFilesTest {

  private HomeDir home;

  @TempDir Path workspaceRoot;

  @BeforeEach
  void setUp() {
    home = new UserHome(workspaceRoot);
  }

  @Test
  @DisplayName("artifactPath resolves a simple filename under the artifacts directory")
  void artifactPathHappyPath() throws Exception {
    final var resolved = FeishuFiles.artifactPath("report.pdf", home);
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");
    assertThat(resolved).isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(resolved.startsWith(artifactsDir)).isTrue();
  }

  @Test
  @DisplayName("artifactPath strips parent directory components to a safe basename")
  void artifactPathStripsParentDirs() throws Exception {
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");

    assertThat(FeishuFiles.artifactPath("subdir/report.pdf", home))
        .isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(FeishuFiles.artifactPath("../etc/passwd", home))
        .isEqualTo(artifactsDir.resolve("passwd"));
    assertThat(FeishuFiles.artifactPath("/etc/passwd", home))
        .isEqualTo(artifactsDir.resolve("passwd"));
  }

  @Test
  @DisplayName("artifactPath rejects dot-segment names")
  void artifactPathRejectsDotSegments() {
    assertThatThrownBy(() -> FeishuFiles.artifactPath("..", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeishuFiles.artifactPath(".", home))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("artifactPath rejects null, blank, and root-only inputs")
  void artifactPathRejectsInvalidInputs() {
    assertThatThrownBy(() -> FeishuFiles.artifactPath(null, home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeishuFiles.artifactPath("", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeishuFiles.artifactPath("   ", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FeishuFiles.artifactPath("/", home))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("artifactPath rejects fileName with embedded NUL (InvalidPathException)")
  void artifactPathRejectsInvalidPath() {
    assertThatThrownBy(() -> FeishuFiles.artifactPath("foo\0bar", home))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
