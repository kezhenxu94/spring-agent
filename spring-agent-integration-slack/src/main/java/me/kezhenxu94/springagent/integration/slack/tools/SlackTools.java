package me.kezhenxu94.springagent.integration.slack.tools;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.slack.SlackFiles;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import me.kezhenxu94.springagent.integration.slack.handler.SlackUserNames;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the agent can do in Slack beyond answering where it was spoken to.
 *
 * <p>Every one of these acts as the bot rather than as the person who asked, which is why {@link
 * SlackChatAccess} stands between the model and any channel that is not the one the run is
 * happening in: a run carries the identity of whoever started it, and without a check the agent
 * would happily read a private channel on their behalf that they cannot see themselves.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class SlackTools {

  private final MethodsClient slack;
  private final SlackProperties properties;
  private final SlackUserNames userNames;
  private final SlackChatAccess chatAccess;
  private final SlackFiles files;

  @Tool(
      name = "SlackSendMessage",
      description =
          "Send a message to a Slack channel or person. Use the thread timestamp to reply inside an"
              + " existing thread rather than starting a new one in the channel.")
  public String sendMessage(
      @ToolParam(description = "Channel id (C…/G…) or user id (U…) to send to") final String target,
      @ToolParam(description = "The message, written as Slack mrkdwn") final String text,
      @ToolParam(required = false, description = "Timestamp of the thread to reply in")
          final String threadTs,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(target, toolContext);
    try {
      final var response =
          slack.chatPostMessage(
              r -> {
                r.channel(target).text(text);
                if (!Strings.isNullOrEmpty(threadTs)) {
                  r.threadTs(threadTs);
                }
                return r;
              });
      if (!response.isOk()) {
        return "Slack refused the message: " + response.getError();
      }
      return "Sent, as message " + response.getTs() + " in " + response.getChannel();
    } catch (Exception e) {
      log.warn("Could not send a Slack message to {}", target, e);
      return "Could not send the message: " + e.getMessage();
    }
  }

  @Tool(
      name = "SlackReadMessageHistory",
      description =
          "Read recent messages from a Slack channel, newest first. Pass a thread timestamp to read"
              + " the replies in one thread instead of the channel.")
  public String readHistory(
      @ToolParam(description = "Channel id to read") final String channelId,
      @ToolParam(required = false, description = "Timestamp of a thread to read instead")
          final String threadTs,
      @ToolParam(required = false, description = "How many messages, at most 100")
          final Integer limit,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(channelId, toolContext);
    final var count = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
    try {
      final List<com.slack.api.model.Message> found;
      if (Strings.isNullOrEmpty(threadTs)) {
        final var response = slack.conversationsHistory(r -> r.channel(channelId).limit(count));
        if (!response.isOk()) {
          return "Slack refused the read: " + response.getError();
        }
        found = response.getMessages();
      } else {
        final var response =
            slack.conversationsReplies(r -> r.channel(channelId).ts(threadTs).limit(count));
        if (!response.isOk()) {
          return "Slack refused the read: " + response.getError();
        }
        found = response.getMessages();
      }
      if (found == null || found.isEmpty()) {
        return "No messages.";
      }
      return found.stream().map(this::render).collect(Collectors.joining("\n"));
    } catch (Exception e) {
      log.warn("Could not read Slack history in {}", channelId, e);
      return "Could not read the history: " + e.getMessage();
    }
  }

  @Tool(
      name = "SlackListChannels",
      description = "List the Slack channels this bot is a member of.")
  public String listChannels(
      @ToolParam(required = false, description = "How many channels, at most 200")
          final Integer limit) {
    final var count = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
    try {
      final var response =
          slack.conversationsList(
              r ->
                  r.limit(count)
                      .excludeArchived(true)
                      // Only what the bot is in: listing every channel in a large workspace is
                      // thousands of names the model has no use for, and most of them are ones it
                      // could not read anyway.
                      .types(
                          List.of(
                              com.slack.api.model.ConversationType.PUBLIC_CHANNEL,
                              com.slack.api.model.ConversationType.PRIVATE_CHANNEL)));
      if (!response.isOk()) {
        return "Slack refused the list: " + response.getError();
      }
      final var channels = response.getChannels();
      if (channels == null || channels.isEmpty()) {
        return "The bot is not in any channel.";
      }
      return channels.stream()
          .filter(com.slack.api.model.Conversation::isMember)
          .map(channel -> "#" + channel.getName() + " (" + channel.getId() + ")")
          .collect(Collectors.joining("\n"));
    } catch (Exception e) {
      log.warn("Could not list Slack channels", e);
      return "Could not list the channels: " + e.getMessage();
    }
  }

  @Tool(
      name = "SlackListChannelMembers",
      description = "List who is in a Slack channel, by name and user id.")
  public String listMembers(
      @ToolParam(description = "Channel id") final String channelId,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(channelId, toolContext);
    try {
      final var response = slack.conversationsMembers(r -> r.channel(channelId).limit(200));
      if (!response.isOk()) {
        return "Slack refused the list: " + response.getError();
      }
      final var members = response.getMembers();
      if (members == null || members.isEmpty()) {
        return "Nobody is in that channel.";
      }
      final var lines = new ArrayList<String>();
      for (final var member : members) {
        lines.add(userNames.nameOf(member) + " (" + member + ")");
      }
      return String.join("\n", lines);
    } catch (Exception e) {
      log.warn("Could not list members of {}", channelId, e);
      return "Could not list the members: " + e.getMessage();
    }
  }

  @Tool(name = "SlackSendFile", description = "Upload a local file into a Slack channel or thread.")
  public String sendFile(
      @ToolParam(description = "Channel id to upload into") final String channelId,
      @ToolParam(description = "Absolute path of the local file") final String path,
      @ToolParam(required = false, description = "A line of text to go with it")
          final String comment,
      @ToolParam(required = false, description = "Timestamp of the thread to upload into")
          final String threadTs,
      final ToolContext toolContext) {
    chatAccess.assertMayReach(channelId, toolContext);
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    try {
      final var file = files.readable(path, userId);
      final var response =
          slack.filesUploadV2(
              r -> {
                r.channel(channelId).file(file).filename(file.getName());
                if (!Strings.isNullOrEmpty(comment)) {
                  r.initialComment(comment);
                }
                if (!Strings.isNullOrEmpty(threadTs)) {
                  r.threadTs(threadTs);
                }
                return r;
              });
      if (!response.isOk()) {
        return "Slack refused the upload: " + response.getError();
      }
      return "Uploaded " + file.getName() + " to " + channelId;
    } catch (Exception e) {
      log.warn("Could not upload {} to {}", path, channelId, e);
      return "Could not upload the file: " + e.getMessage();
    }
  }

  private String render(final com.slack.api.model.Message message) {
    final var who =
        Strings.isNullOrEmpty(message.getUser())
            ? Strings.nullToEmpty(message.getUsername())
            : userNames.nameOf(message.getUser());
    return "[" + message.getTs() + "] " + who + ": " + userNames.resolve(message.getText());
  }
}
