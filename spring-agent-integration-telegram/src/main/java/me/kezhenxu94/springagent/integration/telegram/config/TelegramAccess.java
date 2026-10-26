package me.kezhenxu94.springagent.integration.telegram.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Chat;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.User;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.stereotype.Component;

/**
 * Who may make the agent do anything: start a run, press a button that starts one, or change their
 * model.
 *
 * <p>The question the Slack and Feishu surfaces never have to ask, because a workspace answers it
 * for them — only its members can reach the bot. Nothing answers it on Telegram: a bot is reachable
 * by anybody who finds its name, and a run here acts with the asker's files, credentials and MCP
 * servers and spends this deployment's money. So admission is a list, and an empty list admits
 * nobody — see {@link TelegramProperties#allowedUserIds()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAccess {

  private final TelegramProperties properties;
  private final TelegramBotApi telegram;
  private final TelegramMessages messages;
  private final ProcessedMessageRepo processedMessageRepo;

  /**
   * Whether {@code user}, speaking in {@code chat}, may use the agent: they are named themselves,
   * or the group they are speaking in is.
   */
  public boolean admits(final User user, final Chat chat) {
    if (user == null) {
      return false;
    }
    if (properties.allowedUserIds().contains(String.valueOf(user.id()))) {
      return true;
    }
    return chat != null
        && !chat.isPrivate()
        && properties.allowedChatIds().contains(String.valueOf(chat.id()));
  }

  /**
   * Tells somebody refused in a private chat why, once: their user id is the value an operator has
   * to add, and nobody can be asked to find it themselves. Once per person rather than per message,
   * so a stranger cannot make the bot talk to them at will. Nothing is said in a group, where the
   * people refused are not the ones who could fix it.
   */
  public void refuse(final User user, final Chat chat) {
    log.info(
        "Refusing {} in chat {}: neither is in app.telegram.allowed-user-ids or"
            + " app.telegram.allowed-chat-ids",
        user == null ? null : user.id(),
        chat == null ? null : chat.id());
    if (user == null || chat == null || !chat.isPrivate()) {
      return;
    }
    if (!processedMessageRepo.claim("telegram-refused:" + user.id())) {
      return;
    }
    try {
      telegram.sendMessage(
          String.valueOf(chat.id()),
          null,
          TelegramText.plain(messages.get("access-refused", String.valueOf(user.id()))),
          null,
          null);
    } catch (Exception e) {
      log.warn("Could not tell {} they were refused", user.id(), e);
    }
  }
}
