package me.kezhenxu94.springagent.integration.slack.tools;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Whether the person a run is acting for may reach the channel a tool was pointed at.
 *
 * <p><b>The bot's reach is wider than any one person's, and that is the whole problem.</b> Every
 * Slack call this module makes is made with the bot token, so the bot can read any channel it has
 * been added to — including private ones the person who asked is not in. Without a check,
 * "summarise #leadership" would be answered for anybody who can direct-message the bot.
 *
 * <p>So a channel other than the one the run is happening in has to be one the asker is in too.
 * Checked against Slack rather than against anything stored, because membership changes and a
 * cached answer is a stale permission.
 *
 * <p>An administrator is exempt, which is the same exemption {@code @AgentTool(admin = true)}
 * grants and for the same reason: somebody named in {@code app.ai.admins} is trusted with everyone
 * else's work already.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackChatAccess {

  private final MethodsClient slack;
  private final Admins admins;

  /** Throws unless the run's user may reach {@code target}. */
  public void assertMayReach(final String target, final ToolContext toolContext) {
    if (Strings.isNullOrEmpty(target)) {
      throw new IllegalArgumentException("No channel or user was named");
    }
    final var userId = ToolContexts.get(toolContext, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(userId)) {
      // No identity means no run behind this — a scheduled task, a triage run — and those act as
      // the deployment rather than as a person. Nothing to check them against.
      return;
    }
    // A direct message to the asker themselves, or the channel this run is already happening in.
    if (Objects.equals(target, userId)) {
      return;
    }
    final var current = ToolContexts.get(toolContext, ToolContexts.CHAT_ID);
    if (Objects.equals(target, current)) {
      return;
    }
    if (admins.isAdmin(userId)) {
      return;
    }
    // A user id rather than a channel: sending a direct message to somebody else is not reading
    // their conversations, and refusing it would stop the agent introducing two people.
    if (target.startsWith("U")) {
      return;
    }
    if (!isMember(target, userId)) {
      throw new IllegalStateException(
          "The person this run is for is not in "
              + target
              + ", and the agent will not read a channel on their behalf that they cannot read"
              + " themselves.");
    }
  }

  private boolean isMember(final String channelId, final String userId) {
    try {
      var cursor = (String) null;
      do {
        final var next = cursor;
        final var response =
            slack.conversationsMembers(
                r -> {
                  r.channel(channelId).limit(200);
                  if (!Strings.isNullOrEmpty(next)) {
                    r.cursor(next);
                  }
                  return r;
                });
        if (!response.isOk()) {
          // A channel the bot itself cannot see answers this way. Refusing is the safe reading:
          // the agent cannot establish that the asker is in it.
          log.debug("Could not read the members of {}: {}", channelId, response.getError());
          return false;
        }
        if (response.getMembers() != null && response.getMembers().contains(userId)) {
          return true;
        }
        cursor =
            response.getResponseMetadata() == null
                ? null
                : response.getResponseMetadata().getNextCursor();
      } while (!Strings.isNullOrEmpty(cursor));
      return false;
    } catch (Exception e) {
      log.warn("Could not check whether {} is in {}", userId, channelId, e);
      return false;
    }
  }
}
