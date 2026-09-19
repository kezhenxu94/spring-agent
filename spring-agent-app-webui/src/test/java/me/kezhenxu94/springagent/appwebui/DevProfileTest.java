package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The profile that serves the page out of the source tree.
 *
 * <p>The browser UI lives in {@code spring-agent-integration-websocket}, so an edit to a stylesheet
 * or an ES module reaches a running application only once Gradle has copied it — a build to look at
 * a colour. Under {@code dev} the same files are served from where they are written instead.
 *
 * <p>This exists because the failure is silent. A {@code file:} location that resolves to nothing
 * is not an error: the request falls through to the classpath copy and the page works, showing the
 * version from the last build, which looks exactly like an edit that did not take. So the locations
 * are asserted rather than assumed, and in order — the source tree first, the classpath behind it.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(
    properties = {
      "spring.ai.openai.base-url=http://localhost:1",
      "spring.ai.openai.api-key=test",
      "spring.ai.openai.chat.model=test-model",
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=test",
      "spring.ai.openai.embedding.model=test-embedding",
      "spring.ai.openai.audio.transcription.base-url=http://localhost:1",
      "spring.ai.openai.audio.transcription.api-key=test",
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-dev-test.db",
      "app.web.auth.tenant-id=tenant-under-test"
    })
class DevProfileTest {

  @Autowired WebProperties web;

  @Test
  @DisplayName("the page is served from the source tree first, and the classpath behind it")
  void servesFromTheSourceTree() {
    assertThat(web.getResources().getStaticLocations())
        .containsExactly(
            "file:../spring-agent-integration-websocket/src/main/resources/static/",
            "classpath:/static/");
  }

  @Test
  @DisplayName("and caches none of it, since the file under it changes minute to minute")
  void cachesNothing() {
    assertThat(web.getResources().getCache().getPeriod()).isZero();
  }
}
