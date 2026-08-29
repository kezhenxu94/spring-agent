package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.ListMember;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
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
    throw new ChatAccessDeniedException(
        membership.member() == null
            ? "Refused: whether you are in chat "
                + chatId
                + " could not be established, and this only answers for chats you are in. Say so"
                + " rather than trying another way."
            : "Refused: you are not in chat "
                + chatId
                + ", and this only answers for chats you are in. Say so rather than trying another"
                + " way.");
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
   */
  public Membership membership(final String chatId, final String userId, final int maxPages) {
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
            null, "The member list could not be read: " + resp.getCode() + " " + resp.getMsg());
      }
      final var data = resp.getData();
      final var items = data.getItems() == null ? new ListMember[0] : data.getItems();
      if (Stream.of(items).anyMatch(member -> userId.equals(member.getMemberId()))) {
        return new Membership(true, "Found in the member list of " + chatId + ".");
      }
      if (!Boolean.TRUE.equals(data.getHasMore()) || Strings.isNullOrEmpty(data.getPageToken())) {
        return new Membership(false, "Not in the member list of " + chatId + ", read in full.");
      }
      pageToken = data.getPageToken();
    }
    return new Membership(
        null,
        "Unknown, not no: chat "
            + chatId
            + " has more than "
            + maxPages * MEMBER_PAGE_SIZE
            + " members and its list was not read to the end.");
  }
}
