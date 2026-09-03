package me.kezhenxu94.springagent.appwebfeishu;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * That the person signing in to the page and the person talking to the bot are the same person.
 *
 * <p>This application's whole reason for existing is that they are: a conversation started in
 * Feishu is continued in the browser, and an answer written in the browser goes back to the Feishu
 * chat. Every part of that is keyed on one value — the Feishu {@code open_id} that {@code
 * WebUser.of} reads from the OAuth profile and that {@code FeishuMessageReceiveHandler} reads from
 * a message event.
 *
 * <p><b>And an {@code open_id} is scoped to the Feishu app.</b> Point the OAuth login at one Feishu
 * app and the bot at another and the two ids never coincide — for the same person, in the same
 * enterprise, on the same screen. Nothing throws. No Feishu conversation appears in the sidebar and
 * no mirrored card finds a route, which is indistinguishable from a person who has simply never
 * messaged the bot. That is the worst kind of misconfiguration: invisible from the outside, and
 * only diagnosable by knowing this paragraph.
 *
 * <p>So it is checked once, at startup, and refuses to start. Failing rather than warning follows
 * what {@code app.ai.rag.enabled} does with no Milvus reachable — a deployment is better stopped
 * than running as something other than what it was configured to be — and the case for it here is
 * stronger, because a warning about this would scroll past and the symptom would not point back to
 * it. Both values come from {@code FEISHU_APP_ID} in the shipped {@code application.yaml}, so this
 * fires only for a configuration that deliberately separated them.
 *
 * <p>It checks nothing about the tenant beyond warning: {@code WebAuthoritiesMapper} already
 * refuses a login whose {@code tenant_key} is not {@code app.web.auth.tenant-id}, so a signed-in
 * caller carries this deployment's tenant by the login gate. What that gate cannot do is exist when
 * nobody set the property, which is what the warning is about.
 */
@Slf4j
@Component
public class FeishuIdentityCheck {

  /**
   * The bot's Feishu app id, read as a value rather than from {@code FeishuProperties}.
   *
   * <p>Not squeamishness about coupling: that record has a field typed by the Lark SDK, which the
   * Feishu module keeps as {@code implementation}, so naming the type here would put the SDK on
   * this module's compile classpath to read one string. Defaulted to empty so the failure below is
   * this class's message rather than an unresolved placeholder.
   */
  private final String botAppId;

  private final WebProperties web;
  private final ClientRegistrationRepository registrations;

  public FeishuIdentityCheck(
      @Value("${app.feishu.appId:}") final String botAppId,
      final WebProperties web,
      final ClientRegistrationRepository registrations) {
    this.botAppId = botAppId;
    this.web = web;
    this.registrations = registrations;
  }

  @PostConstruct
  void check() {
    final var provider = web.auth().provider();
    if (!"feishu".equalsIgnoreCase(provider)) {
      // Logging in with one platform and running a bot on another. Not a degraded configuration —
      // there is no sense in which the two identities could line up, so nothing this application
      // is for would work. Named explicitly because the property is shared with
      // spring-agent-app-webui, where any provider is a reasonable choice.
      throw new IllegalStateException(
          "app.web.auth.provider is "
              + provider
              + ", but this application carries the Feishu chat surface and matches a signed-in"
              + " person to their Feishu chat by open_id. Set it to feishu, or run"
              + " spring-agent-app-webui, which takes no chat surface and works with either"
              + " provider.");
    }

    final var registration = registrations.findByRegistrationId(provider);
    if (registration == null) {
      throw new IllegalStateException(
          "No OAuth2 client registration named "
              + provider
              + ". app.web.auth.provider names the registration the sign-in page redirects to, and"
              + " nothing under spring.security.oauth2.client.registration declares it.");
    }

    final var loginApp = nullToEmpty(registration.getClientId());
    final var botApp = nullToEmpty(botAppId);
    if (botApp.isEmpty()) {
      throw new IllegalStateException(
          "app.feishu.appId is not set, so this application carries the Feishu surface with no"
              + " Feishu app to be. Set FEISHU_APP_ID, which the shipped configuration uses for"
              + " both this and the OAuth client id.");
    }
    if (!loginApp.equals(botApp)) {
      throw new IllegalStateException(
          "The Feishu app people sign in with ("
              + loginApp
              + ") is not the one the bot runs as ("
              + botApp
              + "). A Feishu open_id is scoped to the app that issued it, so the same person would"
              + " have two different ids here and nothing would connect their chat to their"
              + " browser: no Feishu conversation in the sidebar, and no chat for an answer to be"
              + " mirrored to. Point spring.security.oauth2.client.registration."
              + provider
              + ".client-id and app.feishu.appId at one app — FEISHU_APP_ID sets both.");
    }

    if (web.auth().tenantId().isEmpty()) {
      log.warn(
          "app.web.auth.tenant-id (FEISHU_TENANT_ID) is not set, so anybody who can complete the"
              + " Feishu login may use this deployment. That is a decision for a browser-only"
              + " server to make; here it also means the only thing keeping one enterprise's"
              + " answers out of another's chat is the per-conversation tenant check in"
              + " ChatMirrors. Set it to this deployment's tenant_key.");
    }
    if (!web.followChatRuns()) {
      log.info(
          "app.web.follow-chat-runs is off, so a Feishu run is readable in the browser once it has"
              + " finished but is not streamed there while it happens. Turn it on to watch one"
              + " live.");
    }
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value.trim();
  }
}
