package me.kezhenxu94.springagent.appwebui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.security.WebAuthoritiesMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * That the Model tab /api/me offers is backed by an endpoint.
 *
 * <p>{@code UserModelController} is conditional on a bean core's auto-configuration defines, so it
 * exists only if the websocket module's auto-configuration is ordered after core's. A unit test
 * constructs the controller by hand and cannot notice; the page notices as a 404, which the real
 * server turns into a trip back to the login page.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-user-models-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.ai.tools.shell.type=none",
      "app.ai.user-models.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
class UserModelEndpointTest {

  @Autowired MockMvc mvc;

  @Test
  @DisplayName("where /api/me says models are enabled, /api/models answers")
  void theTabHasAnEndpoint() throws Exception {
    mvc.perform(get("/api/me").with(login()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.models.enabled").value(true));
    mvc.perform(get("/api/models").with(login()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.models").isArray());
  }

  private static SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor login() {
    return SecurityMockMvcRequestPostProcessors.oauth2Login()
        .oauth2User(
            new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE)),
                Map.of("open_id", "ou_1", "name", "Tester", "tenant_key", "tenant-under-test"),
                "name"));
  }
}
