package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
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
 * That a consumer embedding this server can put a bundle of their own in front of its text — which
 * is how a deployment whose name is written differently in each language gives itself that name,
 * since {@code app.web.title} is deliberately one name for all of them.
 *
 * <p>The half worth pinning is the other one: a bundle that names one key must leave every other
 * key alone. A chain that resolved per file rather than per key would answer every un-named key
 * with the key itself, so a consumer renaming the agent would silently lose every refusal message
 * this module ships — and only in production, where somebody is refused.
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
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-messages-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.web.messages=acme.messages",
      "app.ai.tools.shell.type=none"
    })
class WebMessagesOverrideTest {

  @Autowired MockMvc mvc;
  @Autowired WebMessages messages;
  @Autowired JsonMapper om;

  @Test
  @DisplayName("a consumer's bundle names the agent, and names it per language")
  void aConsumerBundleRenamesTheAgent() throws Exception {
    final var body =
        mvc.perform(
                get("/api/me")
                    .with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(user())))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(om.readValue(body, Map.class).get("title"))
        .isEqualTo(Map.of("en", "Acme Agent", "zh", "艾克米智能体"));
  }

  @Test
  @DisplayName("a key that bundle does not name still comes from this module's own")
  void anUnnamedKeyStillResolves() {
    // Per key, not per file: acme/messages.properties names app-title and nothing else.
    assertThat(messages.get(Locale.ENGLISH, "message-empty"))
        .isEqualTo("A message cannot be empty.");
    assertThat(messages.get(Locale.SIMPLIFIED_CHINESE, "message-empty")).isEqualTo("消息不能为空。");
  }

  private static DefaultOAuth2User user() {
    return new DefaultOAuth2User(
        java.util.List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE)),
        Map.of("open_id", "ou_1", "name", "Tester", "tenant_key", "tenant-under-test"),
        "name");
  }
}
