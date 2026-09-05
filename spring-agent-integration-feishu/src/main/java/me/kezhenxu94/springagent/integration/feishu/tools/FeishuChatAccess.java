package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersReq;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersResp;
import com.lark.oapi.service.im.v1.model.ListMember;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * Whether the person who asked may see a chat at all.
 *
 * <p>Every Feishu call this application makes carries the bot's tenant token, so Feishu authorises
 * the <em>bot</em> and nothing else: a group the bot was invited to is readable by anybody who can
 * talk to the bot, whether or not they are in it. That is wider than what the person could see in
 * their own Feishu client, and it is not a limit the model can be trusted to keep — so the chat
 * tools ask here first, and this is the only place that decides.
 *
 * <p>Two ways in. The run's own chat needs no check: the request was built from a message that
 * person sent in it, so they were in it as of that message — and it costs nothing, which matters
 * because it is nearly every call. Any other chat is checked against its member list.
 *
 * <p>With one exception, and it is not a loophole but the same question asked where the answer is
 * written down. A member list holds people; a bot is in a chat without ever appearing in it. So an
 * identity that <em>is</em> this bot — {@code app.feishu.bot-open-id}, which is what a deployment
 * names as the owner of an unattended run so that it acts as the agent rather than as a person — is
 * never found in any list and would be refused every chat but the run's own. That is not "this
 * identity may not see the chat", it is "this list does not record identities of that kind", and
 * the endpoint that does record it is {@code members/is_in_chat}: it answers for whoever the token
 * represents, which for every call this application makes is exactly the bot. It is asked instead
 * of the walk, so the bot is allowed the chats it is in and no others — an unattended run keeps the
 * same bound as a person's, drawn where its own membership actually lives.
 *
 * <p>Fails closed. A member list that cannot be read to the end leaves the answer unknown, and
 * unknown is refused: a group too large to walk is exactly the kind whose membership is worth
 * keeping.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuChatAccess {

  /**
   * How many member pages a check will read. At the page size below that is 5,000 members — past
   * any group a person is plausibly asked about, and the bound is here so that one question cannot
   * turn into hundreds of requests against a chat_id naming something enormous.
   */
  static final int MAX_MEMBER_PAGES = 50;

  /** Feishu's ceiling, which is also what makes the common group one request rather than ten. */
  static final int MEMBER_PAGE_SIZE = 100;

  /**
   * The bound when filtering a list of chats, where the cost is per chat rather than per call.
   * Smaller on purpose: a group past 300 members drops out of somebody's listing, which costs them
   * a chat_id they can still reach by talking in the group, where the run's own chat needs no check
   * at all.
   */
  static final int MAX_MEMBER_PAGES_WHEN_FILTERING = 3;

  final Client feishu;
  final Admins admins;
  final FeishuProperties feishuProperties;
  final FeishuMessages messages;

  /**
   * @param member true, false, or null where the member list could not be read to the end and so
   *     says nothing either way
   */
  public record Membership(Boolean member, String note) {}

  /** Raised instead of answering, so that a refusal can never be mistaken for an empty result. */
  public static class ChatAccessDeniedException extends RuntimeException {
    public ChatAccessDeniedException(final String message) {
      super(message);
    }
  }

  /**
   * Throws unless the person this run belongs to may see {@code chatId}.
   *
   * @throws ChatAccessDeniedException if they are not in it, or if that could not be established
   */
  public void requireMember(final ToolContext toolContext, final String chatId) {
    if (Strings.isNullOrEmpty(chatId)) {
      throw new IllegalArgumentException("chatId is required");
    }
    // Absent rather than empty on a run with nobody behind it, which is a run with nobody to
    // authorise: throwing here is the fail-closed answer.
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    if (chatId.equals(ToolContexts.get(toolContext, ToolContexts.CHAT_ID))) {
      return;
    }
    if (admins.isAdmin(userId)) {
      log.info("Allowing admin {} into chat {} they may not be a member of", userId, chatId);
      return;
    }
    final var membership = membership(chatId, userId, MAX_MEMBER_PAGES);
    if (Boolean.TRUE.equals(membership.member())) {
      return;
    }
    log.warn("Refused {} access to chat {}: {}", userId, chatId, membership.note());
    // Four sentences rather than two, because the identity behind the run decides which of them is
    // true: told "you are not in that chat", an unattended run owned by the bot has nobody to be,
    // and what gets it fixed — somebody adding the bot to the group — goes unsaid. The message the
    // model reads is the whole of what it can explain the refusal from, so it says which it is.
    throw new ChatAccessDeniedException(
        messages.get(
            isBot(userId)
                ? membership.member() == null ? "access-unknown-chat-bot" : "access-denied-chat-bot"
                : membership.member() == null ? "access-unknown-chat" : "access-denied-chat",
            chatId));
  }

  /**
   * Whether the person this run belongs to is in {@code chatId}, for a caller that has something
   * better to do with the answer than fail — filtering a listing down to their own chats.
   */
  public boolean isMemberForFiltering(final ToolContext toolContext, final String chatId) {
    if (Strings.isNullOrEmpty(chatId)) {
      return false;
    }
    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    if (chatId.equals(ToolContexts.get(toolContext, ToolContexts.CHAT_ID))) {
      return true;
    }
    if (admins.isAdmin(userId)) {
      return true;
    }
    try {
      return Boolean.TRUE.equals(
          membership(chatId, userId, MAX_MEMBER_PAGES_WHEN_FILTERING).member());
    } catch (Exception e) {
      // One unreadable chat should not lose the person the rest of their list; it drops out of it.
      log.warn("Could not tell whether {} is in chat {}, leaving it out", userId, chatId, e);
      return false;
    }
  }

  /**
   * Whether {@code userId} is in {@code chatId}, by walking the member list.
   *
   * <p>Not through {@code members/is_in_chat}, which is what {@code FeishuIsInChat} uses and looks
   * like exactly this question asked in one request. It is not: that endpoint takes a chat_id and
   * nothing else, and answers for whoever the token represents. Every call here carries the app's
   * tenant token, so it can only ever say whether the <em>bot</em> is in the chat — asked on a
   * user's behalf it would return the bot's answer, which would let everybody into every group the
   * bot was ever invited to. Answering for a person would need that person's {@code
   * user_access_token}, which a tool call does not have.
   *
   * <p>Hence the walk, and hence the page bounds: the member list is the only place a third party's
   * membership is written down.
   *
   * <p>Unless the {@code userId} is the bot itself, in which case that endpoint is not the wrong
   * question but the only one that has an answer, and {@link #botMembership} asks it — see the
   * class comment.
   */
  public Membership membership(final String chatId, final String userId, final int maxPages) {
    if (isBot(userId)) {
      return botMembership(chatId);
    }
    String pageToken = null;
    for (int page = 0; page < maxPages; page++) {
      final GetChatMembersReq req =
          GetChatMembersReq.newBuilder()
              .chatId(chatId)
              .memberIdType("open_id")
              .pageSize(MEMBER_PAGE_SIZE)
              .pageToken(pageToken)
              .build();
      final GetChatMembersResp resp;
      try {
        resp = feishu.im().v1().chatMembers().get(req);
      } catch (Exception e) {
        throw new IllegalStateException(
            "Failed to read the members of chat " + chatId + ": " + e.getMessage(), e);
      }
      if (!resp.success()) {
        log.warn(
            "Failed to read the members of chat {}: code={}, msg={}",
            chatId,
            resp.getCode(),
            resp.getMsg());
        // Not an exception: "the bot is not in this chat" comes back this way, and the caller's
        // question — may this person see it — is answered by that, not derailed by it.
        return new Membership(
            null,
            // The code as text, not as a number: an error code put through MessageFormat comes out
            // grouped — 232011 as "232,011" — which is not a code anybody can look up.
            messages.get(
                "chat-membership-unreadable", String.valueOf(resp.getCode()), resp.getMsg()));
      }
      final var data = resp.getData();
      final var items = data.getItems() == null ? new ListMember[0] : data.getItems();
      if (Stream.of(items).anyMatch(member -> userId.equals(member.getMemberId()))) {
        return new Membership(true, messages.get("chat-membership-found", chatId));
      }
      if (!Boolean.TRUE.equals(data.getHasMore()) || Strings.isNullOrEmpty(data.getPageToken())) {
        return new Membership(false, messages.get("chat-membership-absent", chatId));
      }
      pageToken = data.getPageToken();
    }
    return new Membership(
        null, messages.get("chat-membership-too-many", chatId, maxPages * MEMBER_PAGE_SIZE));
  }

  /**
   * Whether {@code userId} is the identity this application itself acts as.
   *
   * <p>Blank or absent where a deployment never configured one, and then this is false for
   * everybody: the check falls back to the member list, which is what it did before, rather than
   * matching a blank user id against a blank property and letting an anonymous run into every chat.
   */
  boolean isBot(final String userId) {
    final var botOpenId = feishuProperties == null ? null : feishuProperties.botOpenId();
    return !Strings.isNullOrEmpty(botOpenId) && botOpenId.equals(userId);
  }

  /**
   * Whether the bot is in {@code chatId}, which is the one membership question a tenant token can
   * answer directly.
   *
   * <p>Fails closed like the walk does: a call that could not be made, or one Feishu refused,
   * leaves the answer unknown rather than yes.
   */
  private Membership botMembership(final String chatId) {
    final IsInChatChatMembersResp resp;
    try {
      resp =
          feishu
              .im()
              .v1()
              .chatMembers()
              .isInChat(IsInChatChatMembersReq.newBuilder().chatId(chatId).build());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to check whether this bot is in chat " + chatId + ": " + e.getMessage(), e);
    }
    if (!resp.success() || resp.getData() == null) {
      log.warn(
          "Failed to check whether this bot is in chat {}: code={}, msg={}",
          chatId,
          resp.getCode(),
          resp.getMsg());
      return new Membership(
          null,
          messages.get(
              "chat-membership-bot-unreadable",
              chatId,
              String.valueOf(resp.getCode()),
              resp.getMsg()));
    }
    final var member = Boolean.TRUE.equals(resp.getData().getIsInChat());
    return new Membership(
        member,
        messages.get(member ? "chat-membership-bot-in" : "chat-membership-bot-out", chatId));
  }
}
