package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class FeishuToolsTest {

  @Mock private UserWorkspaceFactory userWorkspaceFactory;

  private FeishuTools tools;
  private HomeDir home;
  private JsonMapper objectMapper;

  @TempDir Path workspaceRoot;

  @BeforeEach
  void setUp() {
    objectMapper = new JsonMapper();
    home = new UserHome(workspaceRoot);
    tools =
        new FeishuTools(
            null,
            null,
            userWorkspaceFactory,
            objectMapper,
            new FeishuMessages(
                new FeishuProperties(
                    null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null)),
            null);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath resolves a simple filename under the artifacts directory")
  void resolveSafeArtifactPathHappyPath() throws Exception {
    final var resolved = tools.resolveSafeArtifactPath("report.pdf", home);
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");
    assertThat(resolved).isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(resolved.startsWith(artifactsDir)).isTrue();
  }

  @Test
  @DisplayName("resolveSafeArtifactPath strips parent directory components to a safe basename")
  void resolveSafeArtifactPathStripsParentDirs() throws Exception {
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");

    assertThat(tools.resolveSafeArtifactPath("subdir/report.pdf", home))
        .isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(tools.resolveSafeArtifactPath("../etc/passwd", home))
        .isEqualTo(artifactsDir.resolve("passwd"));
    assertThat(tools.resolveSafeArtifactPath("/etc/passwd", home))
        .isEqualTo(artifactsDir.resolve("passwd"));
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects dot-segment names")
  void resolveSafeArtifactPathRejectsDotSegments() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("..", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath(".", home))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects null, blank, and root-only inputs")
  void resolveSafeArtifactPathRejectsInvalidInputs() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath(null, home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("   ", home))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("/", home))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects fileName with embedded NUL (InvalidPathException)")
  void resolveSafeArtifactPathRejectsInvalidPath() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("foo\0bar", home))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
