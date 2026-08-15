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
import org.springframework.core.io.ClassPathResource;
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
            userWorkspaceFactory,
            objectMapper,
            new FeishuMessages(
                new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH)));
    tools.feishuReplyCard = new ClassPathResource("feishu/reply-card.json");
  }

  @Test
  @DisplayName("buildCardContent injects markdown into the message element")
  void injectsMarkdown() throws Exception {
    final var json = tools.buildCardContent("hello **world**");

    final var card = objectMapper.readTree(json);
    final var elements = card.path("body").path("elements");
    final var message =
        java.util.stream.StreamSupport.stream(elements.spliterator(), false)
            .filter(e -> "message".equals(e.path("element_id").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(message.path("content").asString()).isEqualTo("hello **world**");
  }

  @Test
  @DisplayName("buildCardContent disables streaming_mode for one-shot sends")
  void disablesStreamingMode() throws Exception {
    final var json = tools.buildCardContent("anything");

    final var card = objectMapper.readTree(json);
    assertThat(card.path("config").path("streaming_mode").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("buildCardContent strips message_actions and usage (stop button + footer)")
  void stripsMessageActions() throws Exception {
    final var json = tools.buildCardContent("anything");

    final var card = objectMapper.readTree(json);
    final var elements = card.path("body").path("elements");
    assertThat(elements.isArray()).isTrue();
    final var elementIds =
        java.util.stream.StreamSupport.stream(elements.spliterator(), false)
            .map(e -> e.path("element_id").asString())
            .toList();
    assertThat(elementIds).doesNotContain("message_actions", "usage");

    assertThat(json).doesNotContain("\"Stop\"").doesNotContain("\"element_id\":\"stop\"");
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
