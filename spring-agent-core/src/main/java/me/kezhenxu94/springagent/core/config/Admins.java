package me.kezhenxu94.springagent.core.config;

import com.google.common.base.Strings;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Who this deployment trusts with everybody else's work, from {@code app.ai.admins}.
 *
 * <p>A class of its own because the answer is asked in several places that have nothing else to do
 * with each other — a chat membership check, a sandbox label, the tool composition, the run a
 * message joins — and because the set deserves somewhere its meaning is written down. What it
 * grants, in full:
 *
 * <ul>
 *   <li>Reading and posting in Feishu chats they are not a member of ({@code FeishuChatAccess}).
 *   <li>Answering a question the agent asked somebody else, and having their message read into a
 *       run that belongs to somebody else rather than starting a second one.
 *   <li>The tools declared {@code @AgentTool(admin = true)}, which nobody else is offered at all.
 *   <li>A {@code role=admin} label on their shell sandbox, for a cluster that wants to tell them
 *       apart.
 * </ul>
 *
 * <p><b>This is not a UI role.</b> An admin's word steers another person's run, and that run keeps
 * the identity it started with — its files, its credentials, its MCP servers. So an admin can cause
 * things to happen as somebody else, and the set is one to keep to the people who would be trusted
 * with those things directly.
 *
 * <p>For the same reason the reverse pairing is refused outright: an identity that reads text
 * written by strangers must never be listed here, because a run assuming it holds whatever it
 * holds. {@code SituationSweeper} will not start when an event source's {@code owner.user-id} is
 * one of these.
 *
 * <p>Membership is by the surface's own user id, which is whatever that surface puts on a request:
 * a Feishu open id, a CLI user name. There is no directory behind it and no group expansion — a
 * deployment lists the ids it means.
 */
@Component
public class Admins {

  private final SpringAgentProperties properties;

  public Admins(final SpringAgentProperties properties) {
    this.properties = properties;
  }

  /** Whether {@code userId} is one. A blank id never is, so an unattended run is not an admin. */
  public boolean isAdmin(final String userId) {
    return !Strings.isNullOrEmpty(userId) && properties.ai().admins().contains(userId);
  }

  /**
   * Whether the person a tool call belongs to is one.
   *
   * <p>Reads the id rather than requiring it: a run with nobody behind it has no admin behind it
   * either, and answering false is the fail-closed answer where {@code ToolContexts.require} would
   * throw out of a tool that has something better to say.
   */
  public boolean isAdmin(final ToolContext context) {
    return isAdmin(ToolContexts.get(context, ToolContexts.USER_ID));
  }
}
