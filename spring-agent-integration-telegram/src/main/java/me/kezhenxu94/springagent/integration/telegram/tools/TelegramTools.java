package me.kezhenxu94.springagent.integration.telegram.tools;

import com.google.common.base.Strings;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.telegram.TelegramConversation;
import me.kezhenxu94.springagent.integration.telegram.TelegramFiles;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramMarkdown;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the agent can do on Telegram beyond answering where it was spoken to.
 *
 * <p><b>Three where Slack has five, and the two missing are not omissions.</b> The Bot API cannot
 * read a chat's history and cannot list the chats a bot is in or a group's members — a bot sees
 * what it is sent as it arrives and nothing before. A tool offering either would be a tool that
 * always fails, which the model would try anyway. What a bot can ask about a chat is in {@code
 * TelegramGetChat}.
 *
 * <p>Every call acts as the bot, which is why {@link TelegramChatAccess} stands between the model
 * and any chat that is not the one the run is in.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class TelegramTools {

  private final TelegramBotApi telegram;
  private final TelegramChatAccess chatAccess;
  private final TelegramFiles files;

  /** What these hand back to the model, in the chat's language. */
  private final TelegramMessages messages;

  @Tool(
      name = "TelegramSendMessage",
      description =
          "Send a message to a Telegram chat: a group, or a person who has started a chat with this"
              + " bot. Pass a message id to send it as a reply to that message.")
  public String sendMessage(
      @ToolParam(description = "Chat id: a person's user id, or a group's (negative) chat id")
          final String chatId,
      @ToolParam(description = "The message, written as ordinary markdown") final String text,
      @ToolParam(required = false, description = "Id of a message in that chat to reply to")
          final Integer replyToMessageId,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(chatId, toolContext, true);
    try {
      final var sent =
          telegram.sendMessage(
              chatId,
              topicOf(chatId, toolContext),
              TelegramMarkdown.render(text),
              replyToMessageId,
              null);
      return messages.get("tool-sent", String.valueOf(sent.messageId()), chatId);
    } catch (Exception e) {
      log.warn("Could not send a Telegram message to {}", chatId, e);
      return messages.get("tool-send-failed", e.getMessage());
    }
  }

  @Tool(name = "TelegramSendFile", description = "Upload a local file into a Telegram chat.")
  public String sendFile(
      @ToolParam(description = "Chat id to upload into") final String chatId,
      @ToolParam(description = "Absolute path of the local file") final String path,
      @ToolParam(required = false, description = "A line of text to go with it")
          final String caption,
      @ToolParam(required = false, description = "Id of a message in that chat to reply to")
          final Integer replyToMessageId,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(chatId, toolContext, true);
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    try {
      final var file = files.readable(path, userId);
      telegram.sendDocument(chatId, topicOf(chatId, toolContext), file, caption, replyToMessageId);
      return messages.get("tool-uploaded", file.getName(), chatId);
    } catch (Exception e) {
      log.warn("Could not upload {} to {}", path, chatId, e);
      return messages.get("tool-upload-failed", e.getMessage());
    }
  }

  @Tool(
      name = "TelegramGetChat",
      description =
          "Describe a Telegram group: its title, description, how many members it has and who its"
              + " administrators are. Telegram does not let a bot list a group's other members or"
              + " read its earlier messages.")
  public String getChat(
      @ToolParam(description = "The group's chat id") final String chatId,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(chatId, toolContext, false);
    try {
      final var chat = telegram.getChat(chatId);
      final var lines = new ArrayList<String>();
      lines.add(
          messages.get(
              "tool-chat-title",
              Strings.isNullOrEmpty(chat.title()) ? chatId : chat.title(),
              chat.type()));
      if (!Strings.isNullOrEmpty(chat.username())) {
        lines.add("@" + chat.username());
      }
      if (!Strings.isNullOrEmpty(chat.description())) {
        lines.add(chat.description());
      }
      lines.add(messages.get("tool-chat-members", telegram.getChatMemberCount(chatId)));
      final var admins =
          telegram.getChatAdministrators(chatId).stream()
              .filter(member -> member.user() != null)
              .map(
                  member ->
                      member.user().displayName()
                          + (Strings.isNullOrEmpty(member.user().username())
                              ? ""
                              : " (@" + member.user().username() + ")")
                          + " — "
                          + member.user().id())
              .toList();
      if (!admins.isEmpty()) {
        lines.add(messages.get("tool-chat-admins") + "\n" + String.join("\n", admins));
      }
      return String.join("\n", lines);
    } catch (Exception e) {
      log.warn("Could not describe Telegram chat {}", chatId, e);
      return messages.get("tool-chat-failed", e.getMessage());
    }
  }

  /**
   * The forum topic to send into, where the target is the chat the run is in and the run is in a
   * topic: without it the message lands in the group's general topic, away from the question.
   */
  private static Integer topicOf(final String chatId, final ToolContext toolContext) {
    if (!chatId.equals(ToolContexts.get(toolContext, ToolContexts.CHAT_ID))) {
      return null;
    }
    return TelegramConversation.parse(ToolContexts.get(toolContext, ToolContexts.ROOT_MESSAGE_ID))
        .map(TelegramConversation::threadId)
        .orElse(null);
  }
}
