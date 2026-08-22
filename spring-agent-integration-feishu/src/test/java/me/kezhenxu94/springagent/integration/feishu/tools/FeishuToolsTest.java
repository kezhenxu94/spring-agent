package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.nio.file.Path;
import java.util.Locale;
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
  private JsonMapper objectMapper;

  @TempDir Path workspaceRoot;

  @BeforeEach
  void setUp() {
    objectMapper = new JsonMapper();
    lenient()
        .when(userWorkspaceFactory.forOwner(anyString()))
        .thenReturn(new UserHome(workspaceRoot));
    tools =
        new FeishuTools(
            null,
            null,
            userWorkspaceFactory,
            objectMapper,
            new FeishuMessages(
                new FeishuProperties(
                    null, null, null, null, null, null, null, Locale.ENGLISH, null)),
            null);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath resolves a simple filename under the artifacts directory")
  void resolveSafeArtifactPathHappyPath() throws Exception {
    final var resolved = tools.resolveSafeArtifactPath("report.pdf", "user1");
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");
    assertThat(resolved).isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(resolved.startsWith(artifactsDir)).isTrue();
  }

  @Test
  @DisplayName("resolveSafeArtifactPath strips parent directory components to a safe basename")
  void resolveSafeArtifactPathStripsParentDirs() throws Exception {
    final var artifactsDir = workspaceRoot.toAbsolutePath().normalize().resolve("artifacts");

    assertThat(tools.resolveSafeArtifactPath("subdir/report.pdf", "user1"))
        .isEqualTo(artifactsDir.resolve("report.pdf"));
    assertThat(tools.resolveSafeArtifactPath("../etc/passwd", "user1"))
        .isEqualTo(artifactsDir.resolve("passwd"));
    assertThat(tools.resolveSafeArtifactPath("/etc/passwd", "user1"))
        .isEqualTo(artifactsDir.resolve("passwd"));
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects dot-segment names")
  void resolveSafeArtifactPathRejectsDotSegments() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("..", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath(".", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects null, blank, and root-only inputs")
  void resolveSafeArtifactPathRejectsInvalidInputs() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath(null, "user1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("   ", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("/", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("resolveSafeArtifactPath rejects fileName with embedded NUL (InvalidPathException)")
  void resolveSafeArtifactPathRejectsInvalidPath() {
    assertThatThrownBy(() -> tools.resolveSafeArtifactPath("foo\0bar", "user1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
