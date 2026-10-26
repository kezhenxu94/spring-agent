package me.kezhenxu94.springagent.integration.telegram.tools;

import com.google.common.base.Strings;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Whether the person a run is acting for may reach the chat a tool was pointed at — {@code
 * SlackChatAccess}' counterpart, for the same reason: every call is made as the bot, and the bot is
 * in groups the asker is not.
 *
 * <p>A group other than the one the run is in has to be one the asker is in too, asked of Telegram
 * every time. A person is different: a private chat with somebody is a message to them, not a read
 * of anything of theirs, so sending to one is allowed and describing one is not.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramChatAccess {

  private final TelegramBotApi telegram;
  private final Admins admins;
  private final TelegramMessages messages;

  /**
   * Throws unless the run's user may reach {@code target}.
   *
   * @param sending whether the tool only sends to it, which a private chat with another person
   *     allows
   */
  public void assertMayReach(
      final String target, final ToolContext toolContext, final boolean sending) {
    if (Strings.isNullOrEmpty(target)) {
      throw new IllegalArgumentException(messages.get("tool-no-target"));
    }
    final var userId = ToolContexts.get(toolContext, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(userId)) {
      // No person behind the run — a scheduled task, a triage run — so it acts as the deployment.
      return;
    }
    if (Objects.equals(target, userId)
        || Objects.equals(target, ToolContexts.get(toolContext, ToolContexts.CHAT_ID))
        || admins.isAdmin(userId)) {
      return;
    }
    // A group's id is negative and a person's is not.
    if (!target.startsWith("-")) {
      if (sending) {
        return;
      }
      throw new IllegalStateException(messages.get("tool-not-reachable", target));
    }
    if (!isMember(target, userId)) {
      throw new IllegalStateException(messages.get("tool-not-reachable", target));
    }
  }

  private boolean isMember(final String chatId, final String userId) {
    try {
      final var member = telegram.getChatMember(chatId, Long.parseLong(userId));
      return member != null && member.present();
    } catch (Exception e) {
      // A chat the bot is not in answers this way too. Refusing is the safe reading.
      log.debug("Could not check whether {} is in {}: {}", userId, chatId, e.getMessage());
      return false;
    }
  }
}
