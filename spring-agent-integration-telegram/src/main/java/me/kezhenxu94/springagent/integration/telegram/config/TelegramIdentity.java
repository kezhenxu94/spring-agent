package me.kezhenxu94.springagent.integration.telegram.config;

import com.google.common.base.Strings;
import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramApiException;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Bot;
import org.springframework.stereotype.Component;

/**
 * Who this application is on Telegram, established once at startup.
 *
 * <p>Asked of Telegram rather than configured, for the reason {@code SlackIdentity} is: the bot's
 * id and name are what tell a message addressed to it from any other in a group, and a token is the
 * one thing that can say which bot it belongs to. It also fails fast — a refused token otherwise
 * shows up as a poll loop logging 401 every few seconds for ever.
 *
 * <p><b>And it refuses to start over a webhook.</b> Telegram delivers a bot's updates one way at a
 * time: while a webhook is set, {@code getUpdates} is refused. Deleting the webhook would make this
 * work and silently stop whatever the webhook was delivering to — another deployment of the same
 * bot, most likely — so the decision is left to whoever owns both.
 */
@Slf4j
@Component
public class TelegramIdentity {

  /** The bot's own user id, which is also what a run is told its tenant is. */
  @Getter private final long botId;

  /** The bot's username, without the {@code @}, as a mention of it is written. */
  @Getter private final String username;

  public TelegramIdentity(final TelegramBotApi telegram, final TelegramProperties properties) {
    if (Strings.isNullOrEmpty(properties.botToken())) {
      throw new IllegalStateException(
          "app.telegram.bot-token is not set. It is the token BotFather gave when the bot was"
              + " created (/newbot, or /token for an existing one). Set TELEGRAM_BOT_TOKEN.");
    }
    final var me = me(telegram);
    this.botId = me.id();
    this.username = Strings.nullToEmpty(me.username());
    final var webhook = webhook(telegram);
    if (!Strings.isNullOrEmpty(webhook)) {
      throw new IllegalStateException(
          "This bot has a webhook set, and Telegram will not deliver updates by polling while it"
              + " is: whatever the webhook points at is receiving this bot's messages now. If that"
              + " is nothing that should, delete it — curl"
              + " https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/deleteWebhook — and start"
              + " again.");
    }
    log.info("Telegram identity resolved: botId={}, username=@{}", botId, username);
    if (!Boolean.TRUE.equals(me.canReadAllGroupMessages())) {
      // Not an error — a bot used only in private chats wants it on — but the symptom in a group is
      // a bot that never answers a mention, with nothing anywhere saying why.
      log.warn(
          "Privacy mode is on for @{}, so a group sends it only replies to its own messages and"
              + " commands naming it: a plain @{} mention in a group never arrives. Turn it off in"
              + " BotFather (/setprivacy) and re-add the bot to its groups if it should answer"
              + " mentions or watch app.telegram.observed-chat-ids.",
          username,
          username);
    }
    if (properties.allowedUserIds().isEmpty() && properties.allowedChatIds().isEmpty()) {
      log.warn(
          "Neither app.telegram.allowed-user-ids nor app.telegram.allowed-chat-ids is set, so the"
              + " bot will answer nobody. Somebody it refuses is told their own user id, which is"
              + " the value to add.");
    }
  }

  /** The tenant a run is reported under: the bot, whose audience is everyone it admits. */
  public String tenantId() {
    return String.valueOf(botId);
  }

  /** Whether {@code mention}, as typed with its {@code @}, names this bot. */
  public boolean isMe(final String mention) {
    return mention != null
        && !username.isEmpty()
        && mention.toLowerCase(Locale.ROOT).equals("@" + username.toLowerCase(Locale.ROOT));
  }

  private static Bot me(final TelegramBotApi telegram) {
    try {
      return telegram.getMe();
    } catch (TelegramApiException e) {
      throw new IllegalStateException(
          e.unauthorized()
              ? "Telegram refused app.telegram.bot-token. Check TELEGRAM_BOT_TOKEN is the token"
                  + " BotFather issued for this bot, and that it was not revoked since (/revoke)."
              : "Could not reach Telegram to check app.telegram.bot-token: " + e.getMessage(),
          e);
    }
  }

  private static String webhook(final TelegramBotApi telegram) {
    try {
      final var info = telegram.getWebhookInfo();
      return info == null ? null : info.url();
    } catch (TelegramApiException e) {
      throw new IllegalStateException(
          "Could not ask Telegram whether this bot has a webhook: " + e.getMessage(), e);
    }
  }
}
