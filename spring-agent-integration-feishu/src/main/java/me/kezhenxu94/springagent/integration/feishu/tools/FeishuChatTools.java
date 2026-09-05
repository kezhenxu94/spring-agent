package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.DeleteMessageReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatReq;
import com.lark.oapi.service.im.v1.model.GetMessageReq;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersReq;
import com.lark.oapi.service.im.v1.model.ListChat;
import com.lark.oapi.service.im.v1.model.ListChatReq;
import com.lark.oapi.service.im.v1.model.ListMember;
import java.util.List;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The groups the bot belongs to: which they are, what they are, who is in them — and taking back a
 * message it should not have sent.
 *
 * <p>Everything here speaks as the bot, because that is the only identity this application holds a
 * token for. So Feishu authorises the bot and not the person asking, and every tool here goes
 * through {@link FeishuChatAccess} before it answers — otherwise anybody able to talk to the bot
 * could read the membership of every group it was ever invited to. Where a question is genuinely
 * about a person — whether they are in a group — it is answered from the member list rather than
 * from the endpoint that answers for the token holder; see {@link #isInChat}.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuChatTools {

  /**
   * What a caller gets when it names no page size. Both list endpoints reject a size above their
   * own ceiling outright rather than trimming it, so every size here is clamped rather than passed
   * through — see {@link #clamp}.
   */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /**
   * The ceiling on {@link #listChats}, well under Feishu's 100. Each chat in a page costs a
   * membership check of its own — see {@link FeishuChatAccess} — so a page is kept to a size whose
   * worst case is tens of requests rather than hundreds. Paging still reaches every chat.
   */
  private static final int MAX_CHATS_PAGE_SIZE = 20;

  final Client feishu;
  final FeishuChatAccess access;

  @Builder
  @Jacksonized
  public static record ChatSummary(
      String chatId,
      String name,
      String description,
      String ownerId,
      Boolean external,
      String chatStatus) {}

  @Builder
  @Jacksonized
  public static record ChatPage(List<ChatSummary> chats, String nextPageToken, boolean hasMore) {}

  /**
   * The parts of a group worth telling a model about. The endpoint returns some thirty fields;
   * screenshot settings and video-conference permissions are not among the ones an answer turns on.
   */
  @Builder
  @Jacksonized
  public static record ChatInfo(
      String chatId,
      String name,
      String description,
      String chatMode,
      String chatType,
      String chatTag,
      String ownerId,
      String ownerIdType,
      String userCount,
      String botCount,
      Boolean external,
      String chatStatus,
      String addMemberPermission,
      String atAllPermission,
      String moderationPermission,
      String membershipApproval,
      List<String> userManagerIds,
      List<String> botManagerIds) {}

  @Builder
  @Jacksonized
  public static record ChatMember(String memberId, String memberIdType, String name) {}

  @Builder
  @Jacksonized
  public static record ChatMemberPage(
      List<ChatMember> members, Integer memberTotal, String nextPageToken, boolean hasMore) {}

  /**
   * @param member null where the answer is not known — a member list too large to have been read to
   *     the end. False would be read as an answer, and a wrong one.
   */
  @Builder
  @Jacksonized
  public static record ChatMembership(String chatId, String subject, Boolean member, String note) {}

  @Tool(
      name = "FeishuListChats",
      description =
          "List the Feishu groups this bot is in: chat_id, name, description, owner and status."
              + " Use it to find the chat_id of a group the user names, or to see where the bot"
              + " can reach people. Direct message conversations are never in this list, and"
              + " neither are groups the bot was not invited to — a group the user is in but the"
              + " bot is not cannot be seen at all. Only the groups the person you are talking to"
              + " is in are returned, so a page can come back empty or short while hasMore is"
              + " still true: keep passing nextPageToken back until hasMore is false.")
  @SneakyThrows
  public ChatPage listChats(
      @ToolParam(description = "How many to return; 20 by default and at most", required = false)
          final Integer pageSize,
      @ToolParam(
              description = "The nextPageToken of a previous call, to read the page after it",
              required = false)
          final String pageToken,
      @ToolParam(
              description =
                  "\"ByCreateTimeAsc\" (default) or \"ByActiveTimeDesc\". Beware that"
                      + " ByActiveTimeDesc reorders groups as they are used, so paging through it"
                      + " can miss one",
              required = false)
          final String sortType,
      final ToolContext toolContext) {

    final var resp =
        feishu
            .im()
            .v1()
            .chat()
            .list(
                ListChatReq.newBuilder()
                    .userIdType("open_id")
                    .sortType(Strings.isNullOrEmpty(sortType) ? "ByCreateTimeAsc" : sortType)
                    .pageToken(Strings.emptyToNull(pageToken))
                    .pageSize(clamp(pageSize, MAX_CHATS_PAGE_SIZE))
                    .build());

    if (!resp.success()) {
      log.error("Failed to list chats: code={}, msg={}", resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to list chats: " + resp.getMsg());
    }

    final var data = resp.getData();
    final var items = data.getItems() == null ? new ListChat[0] : data.getItems();
    // Filtered rather than refused outright, so that somebody asking which of their groups the bot
    // is in still gets an answer — just not one covering groups they are not in.
    final var visible =
        Stream.of(items).filter(chat -> access.isMemberForFiltering(toolContext, chat.getChatId()));
    log.info("Listing chats the bot is in, {} on this page before filtering", items.length);
    return ChatPage.builder()
        .chats(
            visible
                .map(
                    chat ->
                        ChatSummary.builder()
                            .chatId(chat.getChatId())
                            .name(chat.getName())
                            .description(chat.getDescription())
                            .ownerId(chat.getOwnerId())
                            .external(chat.getExternal())
                            .chatStatus(chat.getChatStatus())
                            .build())
                .toList())
        .nextPageToken(data.getPageToken())
        .hasMore(Boolean.TRUE.equals(data.getHasMore()))
        .build();
  }

  @Tool(
      name = "FeishuGetChat",
      description =
          "Read one Feishu group's details: name, description, owner, member and bot counts,"
              + " whether it is external, and its permissions — who may add members, who may"
              + " notify everybody (atAllPermission), who may speak, whether joining needs"
              + " approval. Ask this before using an @all mention, and to answer questions about"
              + " how a group is set up. The bot has to be in the group for anything beyond the"
              + " name, avatar, member count and status to come back, and so does the person you"
              + " are talking to.")
  @SneakyThrows
  public ChatInfo getChat(
      @ToolParam(description = "The group's chat_id, of the form oc_xxx") final String chatId,
      final ToolContext toolContext) {

    access.requireMember(toolContext, chatId);

    final var resp =
        feishu
            .im()
            .v1()
            .chat()
            .get(GetChatReq.newBuilder().chatId(chatId).userIdType("open_id").build());

    if (!resp.success()) {
      log.error("Failed to get chat {}: code={}, msg={}", chatId, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to get chat " + chatId + ": " + resp.getMsg());
    }

    final var data = resp.getData();
    log.info("Read chat info for {}", chatId);
    return ChatInfo.builder()
        .chatId(chatId)
        .name(data.getName())
        .description(data.getDescription())
        .chatMode(data.getChatMode())
        .chatType(data.getChatType())
        .chatTag(data.getChatTag())
        .ownerId(data.getOwnerId())
        .ownerIdType(data.getOwnerIdType())
        .userCount(data.getUserCount())
        .botCount(data.getBotCount())
        .external(data.getExternal())
        .chatStatus(data.getChatStatus())
        .addMemberPermission(data.getAddMemberPermission())
        .atAllPermission(data.getAtAllPermission())
        .moderationPermission(data.getModerationPermission())
        .membershipApproval(data.getMembershipApproval())
        .userManagerIds(toList(data.getUserManagerIdList()))
        .botManagerIds(toList(data.getBotManagerIdList()))
        .build();
  }

  @Tool(
      name = "FeishuListChatMembers",
      description =
          "List the people in a Feishu group, with their open_id and name. Use it to find"
              + " somebody's open_id so you can mention them, or to report who is in a group."
              + " Bots are left out by the API, so the count here can differ from the group's"
              + " user count, and the bot itself has to be in the group to read it. Results are"
              + " paginated: pass nextPageToken back to get the next page. When listing these"
              + " people in a reply, render them with <person> rather than <at> so they are shown"
              + " rather than notified.")
  @SneakyThrows
  public ChatMemberPage listChatMembers(
      @ToolParam(description = "The group's chat_id, of the form oc_xxx") final String chatId,
      @ToolParam(description = "How many to return; 20 by default, 100 at most", required = false)
          final Integer pageSize,
      @ToolParam(
              description = "The nextPageToken of a previous call, to read the page after it",
              required = false)
          final String pageToken,
      final ToolContext toolContext) {

    access.requireMember(toolContext, chatId);
    final var page =
        readMemberPage(
            chatId,
            clamp(pageSize, FeishuChatAccess.MEMBER_PAGE_SIZE),
            Strings.emptyToNull(pageToken));
    log.info("Listed {} member(s) of chat {}", page.members().size(), chatId);
    return page;
  }

  @Tool(
      name = "FeishuIsInChat",
      description =
          "Check whether somebody is in a Feishu group. Leave userId out to ask about the bot"
              + " itself — worth doing before trying to send to a group, since a bot that is not a"
              + " member cannot post there. Give a userId (an open_id) to ask about a person,"
              + " which is answered by reading the group's member list, so the bot has to be in"
              + " the group for it to be answerable at all.")
  @SneakyThrows
  public ChatMembership isInChat(
      @ToolParam(description = "The group's chat_id, of the form oc_xxx") final String chatId,
      @ToolParam(
              description =
                  "The open_id of the person to ask about. Leave it out to ask about the bot",
              required = false)
          final String userId,
      final ToolContext toolContext) {

    access.requireMember(toolContext, chatId);
    if (Strings.isNullOrEmpty(userId)) {
      final var resp =
          feishu
              .im()
              .v1()
              .chatMembers()
              .isInChat(IsInChatChatMembersReq.newBuilder().chatId(chatId).build());
      if (!resp.success()) {
        log.error(
            "Failed to check whether the bot is in chat {}: code={}, msg={}",
            chatId,
            resp.getCode(),
            resp.getMsg());
        throw new IllegalStateException(
            "Failed to check whether the bot is in chat " + chatId + ": " + resp.getMsg());
      }
      final var member = Boolean.TRUE.equals(resp.getData().getIsInChat());
      log.info("Bot {} in chat {}", member ? "is" : "is not", chatId);
      return ChatMembership.builder()
          .chatId(chatId)
          .subject("bot")
          .member(member)
          .note("Answered for this bot, which is the identity this application acts as.")
          .build();
    }

    // No endpoint answers this for anybody but the token holder, so the member list is the answer
    // for a person. Bots are not in that list, which is why the bot's own case above is asked of
    // is_in_chat instead — and why FeishuChatAccess asks the same thing when the named open_id is
    // this bot's own, rather than reporting it absent from a list it could never appear in.
    final var membership = access.membership(chatId, userId, FeishuChatAccess.MAX_MEMBER_PAGES);
    log.info("User {} in chat {}: {}", userId, chatId, membership.member());
    return ChatMembership.builder()
        .chatId(chatId)
        .subject(userId)
        .member(membership.member())
        .note(
            membership.member() == null
                ? membership.note() + " Say you could not tell rather than reporting them absent."
                : membership.note())
        .build();
  }

  @Tool(
      name = "FeishuRecallMessage",
      description =
          "Recall (delete) a Feishu message, taking it out of the conversation for everybody."
              + " This cannot be undone, so ask the user before calling it unless they have"
              + " already asked for this exact message to go. The bot may only recall its own"
              + " messages, unless it is the group's owner or an administrator; a message the"
              + " workspace considers too old to recall is refused, as is one sent by the batch"
              + " send API. To recall the bot's own last answer, the message id is the one the"
              + " reply card was sent as. Only messages in a chat the person you are talking to is"
              + " in can be recalled.")
  @SneakyThrows
  public String recallMessage(
      @ToolParam(description = "Id of the message to recall, of the form om_xxx")
          final String messageId,
      final ToolContext toolContext) {

    // Which chat it is in is not something the caller states, so it is read off the message: a
    // recall is a write into somebody's conversation, and the id alone says nothing about whose.
    final var lookup =
        feishu.im().v1().message().get(GetMessageReq.newBuilder().messageId(messageId).build());
    if (!lookup.success()) {
      log.warn(
          "Could not read message {} before recalling it: code={}, msg={}",
          messageId,
          lookup.getCode(),
          lookup.getMsg());
      return "Failed to recall message "
          + messageId
          + ": it could not be read first ("
          + lookup.getCode()
          + " "
          + lookup.getMsg()
          + ")";
    }
    final var found = lookup.getData().getItems();
    if (found == null || found.length == 0) {
      return "Failed to recall message " + messageId + ": no such message";
    }
    access.requireMember(toolContext, found[0].getChatId());

    final var resp =
        feishu
            .im()
            .v1()
            .message()
            .delete(DeleteMessageReq.newBuilder().messageId(messageId).build());

    if (!resp.success()) {
      log.error(
          "Failed to recall message {} for user {}: code={}, msg={}",
          messageId,
          ToolContexts.get(toolContext, ToolContexts.USER_ID),
          resp.getCode(),
          resp.getMsg());
      return "Failed to recall message " + messageId + ": " + resp.getCode() + " " + resp.getMsg();
    }
    log.info(
        "Recalled message {} for user {}",
        messageId,
        ToolContexts.get(toolContext, ToolContexts.USER_ID));
    return "Message " + messageId + " recalled.";
  }

  /** One page of members, shared by the listing tool and the membership check. */
  private ChatMemberPage readMemberPage(
      final String chatId, final int pageSize, final String pageToken) throws Exception {
    final var resp =
        feishu
            .im()
            .v1()
            .chatMembers()
            .get(
                GetChatMembersReq.newBuilder()
                    .chatId(chatId)
                    .memberIdType("open_id")
                    .pageSize(pageSize)
                    .pageToken(pageToken)
                    .build());

    if (!resp.success()) {
      log.error(
          "Failed to list members of chat {}: code={}, msg={}",
          chatId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException(
          "Failed to list members of chat " + chatId + ": " + resp.getMsg());
    }

    final var data = resp.getData();
    final var items = data.getItems() == null ? new ListMember[0] : data.getItems();
    return ChatMemberPage.builder()
        .members(
            Stream.of(items)
                .map(
                    member ->
                        ChatMember.builder()
                            .memberId(member.getMemberId())
                            .memberIdType(member.getMemberIdType())
                            .name(member.getName())
                            .build())
                .toList())
        .memberTotal(data.getMemberTotal())
        .nextPageToken(data.getPageToken())
        .hasMore(Boolean.TRUE.equals(data.getHasMore()))
        .build();
  }

  private static int clamp(final Integer pageSize, final int max) {
    return pageSize == null
        ? Math.min(DEFAULT_PAGE_SIZE, max)
        : Math.min(Math.max(pageSize, 1), max);
  }

  private static List<String> toList(final String[] values) {
    return values == null ? List.of() : Stream.of(values).toList();
  }
}
