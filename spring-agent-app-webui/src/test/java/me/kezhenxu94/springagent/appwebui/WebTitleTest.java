package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.config.WebLocaleConfiguration;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * That a deployment which renamed itself is called that whatever language it is read in.
 *
 * <p>The failure is silent in both directions: a name that reaches only the language the page
 * started in goes back to Spring Agent the moment somebody uses the switcher, and one that is
 * translated is a deployment being called something it never chose.
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
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-title-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.web.title=Acme Agent",
      "app.ai.tools.shell.type=none"
    })
class WebTitleTest {

  @Autowired MockMvc mvc;
  @Autowired JsonMapper om;

  private Map<?, ?> me() throws Exception {
    final var body =
        mvc.perform(
                get("/api/me")
                    .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(user()))
                    .cookie(new Cookie(WebLocaleConfiguration.LOCALE_COOKIE, "en")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return om.readValue(body, Map.class);
  }

  @Test
  @DisplayName("a name a deployment chose for itself is its name in every language")
  void aConfiguredNameIsNotTranslated() throws Exception {
    // Every supported language, not only the reader's: the switcher never asks the server again, so
    // a language missing here is a language the tab would go back to Spring Agent in.
    assertThat(me().get("title")).isEqualTo(Map.of("en", "Acme Agent", "zh", "Acme Agent"));
  }

  private static DefaultOAuth2User user() {
    return new DefaultOAuth2User(
        java.util.List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE)),
        Map.of("open_id", "ou_1", "name", "Tester", "tenant_key", "tenant-under-test"),
        "name");
  }
}
