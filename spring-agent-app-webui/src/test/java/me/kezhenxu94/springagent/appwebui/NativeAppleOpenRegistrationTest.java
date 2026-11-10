package me.kezhenxu94.springagent.appwebui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign in with Apple where a deployment has opened registration: the person gets a token, an
 * identity of {@code apple:<sub>}, and no tenant.
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
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-native-apple-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.ai.tools.shell.type=none",
      "app.web.auth.native.token-secret=a passphrase that is long enough to be a key!",
      "app.web.auth.native.apple-client-ids=com.example.agent",
      "app.web.auth.native.open-registration=true"
    })
class NativeAppleOpenRegistrationTest {

  static final AppleKeys APPLE = AppleKeys.start();

  @DynamicPropertySource
  static void apple(final DynamicPropertyRegistry registry) {
    registry.add("app.web.auth.native.apple-jwks-uri", APPLE::jwksUri);
  }

  @AfterAll
  static void stop() {
    APPLE.stop();
  }

  @Autowired MockMvc mvc;
  private final JsonMapper om = JsonMapper.builder().build();

  @Test
  @DisplayName("an apple sign-in yields a token for apple:<sub> with no tenant")
  void appleSignIn() throws Exception {
    final var body =
        mvc.perform(
                post("/auth/native/apple")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        om.writeValueAsString(
                            Map.of(
                                "identity_token",
                                APPLE.identityToken("com.example.agent", "001.apple"),
                                "name",
                                "Ann"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.userId").value("apple:001.apple"))
            .andExpect(jsonPath("$.user.name").value("Ann"))
            .andExpect(jsonPath("$.user.tenantId").value(""))
            .andReturn()
            .getResponse()
            .getContentAsString();
    final var token = (String) om.readValue(body, Map.class).get("access_token");

    mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("apple:001.apple"))
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.knowledge.tenant").value(false));
  }
}
