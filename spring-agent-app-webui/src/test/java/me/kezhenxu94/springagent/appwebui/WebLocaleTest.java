package me.kezhenxu94.springagent.appwebui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import me.kezhenxu94.springagent.integration.websocket.config.WebLocaleConfiguration;
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
 * That a reader gets their own language without asking, and the one they asked for when they did.
 *
 * <p>Worth pinning because the failure is silent: a server that resolves the locale once at startup
 * still answers every request, just always in the same language, and nothing about that looks
 * broken until somebody who reads Chinese opens the page.
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
      // And the transcription endpoint, which application.yaml points at
      // ${TRANSCRIPTION_OPENAI_BASE_URL} with no default: without these the context only refreshes
      // on a machine that happens to export it.
      "spring.ai.openai.audio.transcription.base-url=http://localhost:1",
      "spring.ai.openai.audio.transcription.api-key=test",
      "spring.security.oauth2.client.registration.feishu.client-id=test",
      "spring.security.oauth2.client.registration.feishu.client-secret=test",
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-web-locale-test.db",
      "app.web.auth.tenant-id=tenant-under-test",
      "app.ai.tools.shell.type=none"
    })
class WebLocaleTest {

  @Autowired MockMvc mvc;
  @Autowired WebMessages messages;
  @Autowired JsonMapper om;

  private String localeOf(final String acceptLanguage, final Cookie cookie) throws Exception {
    var request =
        get("/api/me").with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(user()));
    if (acceptLanguage != null) {
      request = request.header("Accept-Language", acceptLanguage);
    }
    if (cookie != null) {
      request = request.cookie(cookie);
    }
    final var body = mvc.perform(request).andReturn().getResponse().getContentAsString();
    return (String) om.readValue(body, Map.class).get("locale");
  }

  @Test
  @DisplayName("a browser that asks for Chinese is answered in Chinese, with nothing configured")
  void acceptLanguageIsHonoured() throws Exception {
    assertThat(localeOf("zh-CN,zh;q=0.9,en;q=0.8", null)).isEqualTo("zh-CN");
    assertThat(localeOf("en-GB,en;q=0.9", null)).isEqualTo("en");
  }

  @Test
  @DisplayName("a language nothing is written in falls back rather than half-translating")
  void anUnsupportedLanguageFallsBack() throws Exception {
    // Matching an arbitrary Accept-Language would mean asking for a bundle that does not exist and
    // falling back key by key, which lands a page half in one language and half in another.
    assertThat(localeOf("fr-FR,fr;q=0.9", null)).isEqualTo("en");
  }

  @Test
  @DisplayName("traditional Chinese gets simplified Chinese, which is closer than English")
  void chineseIsMatchedByLanguageNotByTag() throws Exception {
    assertThat(localeOf("zh-TW", null)).isEqualTo("zh-CN");
  }

  @Test
  @DisplayName("the switcher's cookie beats what the browser asked for")
  void theCookieWins() throws Exception {
    // The switcher writes this cookie, which is also what the server reads — so choosing a language
    // changes the server's own messages and not only the page's labels.
    final var cookie = new Cookie(WebLocaleConfiguration.LOCALE_COOKIE, "zh-CN");
    assertThat(localeOf("en-GB,en;q=0.9", cookie)).isEqualTo("zh-CN");
  }

  @Test
  @DisplayName("the server's own text is translated, not just the page's labels")
  void serverMessagesAreTranslated() {
    // WebMessages reads LocaleContextHolder, which the resolver fills in per request. Held here
    // directly, since the messages this covers are refusal reasons rather than a response body.
    org.springframework.context.i18n.LocaleContextHolder.setLocale(
        java.util.Locale.SIMPLIFIED_CHINESE);
    try {
      assertThat(messages.get("message-empty")).isEqualTo("消息不能为空。");
    } finally {
      org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
    try {
      assertThat(messages.get("message-empty")).isEqualTo("A message cannot be empty.");
    } finally {
      org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }
  }

  @Test
  @DisplayName("a deployment that never renamed itself is named by the bundle, in each language")
  void anUnconfiguredNameComesFromTheBundle() throws Exception {
    // The name is app-title, a translated string like every other, and every supported language is
    // sent at once because the switcher retitles the tab without asking the server again.
    final var request =
        get("/api/me").with(SecurityMockMvcRequestPostProcessors.oauth2Login().oauth2User(user()));
    final var body = mvc.perform(request).andReturn().getResponse().getContentAsString();
    assertThat(om.readValue(body, Map.class).get("title"))
        .isEqualTo(Map.of("en", "Spring Agent", "zh", "Spring 智能体"));
  }

  @Test
  @DisplayName("every key the page can reach exists in both bundles")
  void bothBundlesAreComplete() {
    // A missing key degrades to the key itself, which reads as gibberish rather than as an error.
    final var keys =
        java.util.List.of(
            "app-title",
            "message-empty",
            "question-empty",
            "question-expired",
            "question-answered",
            "question-superseded",
            "question-pending");
    for (final var language : WebLocaleConfiguration.SUPPORTED) {
      org.springframework.context.i18n.LocaleContextHolder.setLocale(language);
      try {
        for (final var key : keys) {
          assertThat(messages.get(key)).as("%s in %s", key, language).isNotEqualTo(key);
        }
      } finally {
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
      }
    }
  }

  private static DefaultOAuth2User user() {
    return new DefaultOAuth2User(
        java.util.List.of(new SimpleGrantedAuthority(WebAuthoritiesMapper.ROLE)),
        Map.of("open_id", "ou_1", "name", "Tester", "tenant_key", "tenant-under-test"),
        "name");
  }
}
