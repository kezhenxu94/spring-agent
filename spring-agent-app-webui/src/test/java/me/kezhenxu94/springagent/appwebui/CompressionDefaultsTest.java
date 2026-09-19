package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * That what this page sends is compressed.
 *
 * <p>Boot leaves {@code server.compression} off, and this surface ships a great deal of text: the
 * Tailwind build is 282KB, highlight.js 125KB, and every skill file, knowledge document and
 * transcript on top of that. Uncompressed they are three to five times what they need to be.
 *
 * <p>Bound rather than parsed, which is the whole reason this is a test and not a line in a README:
 * a {@code compression:} block one level off is YAML a parser accepts and Boot ignores in silence,
 * leaving the default of `off` with nothing anywhere to say so. The same trap {@code
 * DockerShellDefaultsTest} names, and the same guard.
 *
 * <p><b>Not octet-stream</b>, and that is the assertion worth reading twice: a skill downloads as a
 * zip, which is already compressed, and gzipping it again spends CPU to make it very slightly
 * bigger.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.ai.openai.base-url=http://localhost:1",
      "spring.ai.openai.api-key=test",
      "spring.ai.openai.chat.model=test-model",
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=test",
      "spring.ai.openai.embedding.model=test-embedding",
      // And the transcription endpoint, which application.yaml points at
      // ${TRANSCRIPTION_OPENAI_BASE_URL} with no default: without these the context only refreshes
      // on a machine that happens to export it.
      "spring.ai.openai.audio.transcription.base-url=http://localhost:1",
      "spring.ai.openai.audio.transcription.api-key=test",
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-compression-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      // The backend under test, which is otherwise `none` and would register no properties bean at
      // all. The encryption key is refused when blank, and blank is the yaml's default — the point
      // of that default being that a deployment has to choose one, so the test chooses one too.
      "app.ai.tools.shell.type=docker",
      "app.ai.tools.shell.docker.credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    })
class CompressionDefaultsTest {

  @Autowired ServerProperties server;

  @Test
  @DisplayName("responses are compressed, and the block is at a level Boot actually reads")
  void compressionIsOn() {
    assertThat(server.getCompression().getEnabled()).isTrue();
  }

  @Test
  @DisplayName("the text this page is mostly made of is on the list")
  void coversWhatIsActuallySent() {
    assertThat(server.getCompression().getMimeTypes())
        .contains(
            "application/json", "application/javascript", "text/css", "text/html", "text/plain");
  }

  @Test
  @DisplayName("a download is left alone, because a zip does not compress twice")
  void leavesBinaryAlone() {
    assertThat(server.getCompression().getMimeTypes())
        .doesNotContain("application/octet-stream")
        .doesNotContain("*/*");
  }

  @Test
  @DisplayName("a small answer is not compressed, since the header would cost more than it saves")
  void skipsSmallAnswers() {
    assertThat(server.getCompression().getMinResponseSize()).isEqualTo(DataSize.ofKilobytes(1));
  }
}
