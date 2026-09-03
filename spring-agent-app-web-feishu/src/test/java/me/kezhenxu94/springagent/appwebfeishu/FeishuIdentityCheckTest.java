package me.kezhenxu94.springagent.appwebfeishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * The check that turns the one misconfiguration nothing else would report into a startup failure.
 */
class FeishuIdentityCheckTest {

  private static ClientRegistrationRepository loginWith(final String clientId) {
    final var registration =
        ClientRegistration.withRegistrationId("feishu")
            .clientId(clientId)
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri("https://example.invalid/authorize")
            .tokenUri("https://example.invalid/token")
            .redirectUri("https://example.invalid/callback")
            .userNameAttributeName("name")
            .build();
    return id -> "feishu".equals(id) ? registration : null;
  }

  private static WebProperties web(final String provider, final String tenantId) {
    return new WebProperties(
        null,
        null,
        new WebProperties.Auth(provider, tenantId),
        null,
        null,
        null,
        null,
        null,
        false,
        List.of());
  }

  private static FeishuIdentityCheck check(
      final String botAppId, final String loginAppId, final String provider, final String tenant) {
    return new FeishuIdentityCheck(botAppId, web(provider, tenant), loginWith(loginAppId));
  }

  @Test
  @DisplayName("one Feishu app for both, which is what the shipped configuration gives, is fine")
  void oneAppIsFine() {
    assertThatCode(() -> check("cli_1", "cli_1", "feishu", "tenant-1").check())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("two different Feishu apps stop the application, saying why open_id will not match")
  void twoAppsRefuseToStart() {
    // The whole reason this class exists. Nothing would throw at runtime: the sidebar would simply
    // show no Feishu conversations, exactly as it does for somebody who never messaged the bot.
    assertThatThrownBy(() -> check("cli_bot", "cli_login", "feishu", "tenant-1").check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cli_bot")
        .hasMessageContaining("cli_login")
        .hasMessageContaining("open_id is scoped to the app")
        .hasMessageContaining("FEISHU_APP_ID");
  }

  @Test
  @DisplayName("signing in with another platform stops it too, and names the alternative")
  void anotherLoginProviderRefusesToStart() {
    assertThatThrownBy(() -> check("cli_1", "cli_1", "slack", "tenant-1").check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring-agent-app-webui");
  }

  @Test
  @DisplayName("no bot app at all is a Feishu surface with no Feishu app to be")
  void noBotAppRefusesToStart() {
    assertThatThrownBy(() -> check(null, "cli_1", "feishu", "tenant-1").check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.feishu.appId");
  }

  @Test
  @DisplayName("a registration nobody declared is named rather than dereferenced")
  void aMissingRegistrationRefusesToStart() {
    final var noRegistrations = new FeishuIdentityCheck("cli_1", web("feishu", "t"), id -> null);
    assertThatThrownBy(noRegistrations::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No OAuth2 client registration");
  }

  @Test
  @DisplayName("an unset tenant is a warning and not a refusal, since a deployment may mean it")
  void anUnsetTenantStillStarts() {
    // WebAuthoritiesMapper is what refuses a login, and it already reports this. Failing here would
    // stop a deployment that spring-agent-app-webui allows, over a decision that is not this
    // class's to make.
    assertThatCode(() -> check("cli_1", "cli_1", "feishu", "").check()).doesNotThrowAnyException();
    assertThat(web("feishu", "").auth().tenantId()).isEmpty();
  }
}
