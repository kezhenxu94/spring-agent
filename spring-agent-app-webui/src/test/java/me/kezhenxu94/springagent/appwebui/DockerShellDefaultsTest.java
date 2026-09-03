package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import me.kezhenxu94.springagent.tools.shell.docker.DockerShellProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * That the docker sandbox is configured the same way here as on the server.
 *
 * <p>The values are duplicated between the two {@code application.yaml}s rather than shared,
 * because each application's file is its own configuration reference and neither should have to be
 * read beside the other to be understood. The cost of that is drift, and drift here is the quiet
 * kind: a sandbox that reaps at a different time or caps memory differently on one surface than the
 * other, with nothing failing to say so. This is what notices.
 *
 * <p>Bound rather than parsed, so it also catches the block being at the wrong nesting level —
 * which YAML would accept and Boot would silently ignore, leaving every value at its code default.
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
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-docker-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      // The backend under test, which is otherwise `none` and would register no properties bean at
      // all. The encryption key is refused when blank, and blank is the yaml's default — the point
      // of that default being that a deployment has to choose one, so the test chooses one too.
      "app.ai.tools.shell.type=docker",
      "app.ai.tools.shell.docker.credentials.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    })
class DockerShellDefaultsTest {

  @Autowired DockerShellProperties docker;

  @Test
  @DisplayName("the docker sandbox defaults are the server's, not the record's fallbacks")
  void defaultsMatchTheServer() {
    // Each of these is stated in application.yaml. A value here that happens to equal the record's
    // own fallback would prove nothing, so the assertions that matter are `image` and the two
    // resource ceilings, none of which the record defaults at all.
    assertThat(docker.image()).isEqualTo("ghcr.io/kezhenxu94/spring-agent/shell-runner:latest");
    assertThat(docker.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
    assertThat(docker.hardDeadline()).isEqualTo(Duration.ofHours(4));
    assertThat(docker.startupTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(docker.defaultTimeoutMs()).isEqualTo(120_000L);
    assertThat(docker.maxTimeoutMs()).isEqualTo(600_000L);
    assertThat(docker.resources().cpuLimit()).isEqualTo("1000m");
    assertThat(docker.resources().memoryLimit()).isEqualTo("1Gi");
    assertThat(docker.credentials().mountPathOrDefault()).isEqualTo("/run/secrets/credentials");
    // Left to the daemon's own default; stated as a commented line in the yaml rather than a value.
    assertThat(docker.network()).isNull();
  }
}
