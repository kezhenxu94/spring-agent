package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.V1;
import com.lark.oapi.service.im.v1.model.DeleteMessageResp;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.GetChatMembersRespBody;
import com.lark.oapi.service.im.v1.model.GetChatResp;
import com.lark.oapi.service.im.v1.model.GetChatRespBody;
import com.lark.oapi.service.im.v1.model.GetMessageResp;
import com.lark.oapi.service.im.v1.model.GetMessageRespBody;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersResp;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersRespBody;
import com.lark.oapi.service.im.v1.model.ListChat;
import com.lark.oapi.service.im.v1.model.ListChatReq;
import com.lark.oapi.service.im.v1.model.ListChatResp;
import com.lark.oapi.service.im.v1.model.ListChatRespBody;
import com.lark.oapi.service.im.v1.model.ListMember;
import com.lark.oapi.service.im.v1.resource.Chat;
import com.lark.oapi.service.im.v1.resource.ChatMembers;
import com.lark.oapi.service.im.v1.resource.Message;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuChatAccess.ChatAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuChatToolsTest {

  @Mock private com.lark.oapi.Client feishu;
  @Mock private ImService im;
  @Mock private V1 v1;
  @Mock private Chat chat;
  @Mock private ChatMembers chatMembers;
  @Mock private Message message;

  private FeishuChatTools tools;

  @BeforeEach
  void setUp() {
    when(feishu.im()).thenReturn(im);
    when(im.v1()).thenReturn(v1);
    when(v1.chat()).thenReturn(chat);
    when(v1.chatMembers()).thenReturn(chatMembers);
    when(v1.message()).thenReturn(message);
    tools =
        new FeishuChatTools(
            feishu,
            new FeishuChatAccess(
                feishu,
                new Admins(
                    new SpringAgentProperties(
                        null,
                        new SpringAgentProperties.Ai(
                            Set.of(), Map.of(), null, null, null, null, null, null),
                        Locale.ENGLISH,
                        null,
                        null)),
                botOpenId("ou_bot")));
  }

  /** The chat the run is in, which is the one the asking user needs no lookup to be allowed. */
  private static ToolContext toolContext() {
    return new ToolContext(
        Map.of(ToolContexts.KEY_USER_ID, "ou_asking", ToolContexts.KEY_CHAT_ID, "oc_1"));
  }

  /** A response as the SDK hands one back when Feishu refuses the call. */
  private static <T extends BaseResponse<?>> T failing(
      final T resp, final int code, final String msg) {
    resp.setCode(code);
    resp.setMsg(msg);
    return resp;
  }

  private static ListChatResp chatsPage(final boolean hasMore, final String pageToken) {
    final var body = new ListChatRespBody();
    final var one = new ListChat();
    one.setChatId("oc_1");
    one.setName("Release war room");
    one.setDescription("ships on Thursdays");
    one.setOwnerId("ou_owner");
    one.setExternal(false);
    one.setChatStatus("normal");
    body.setItems(new ListChat[] {one});
    body.setHasMore(hasMore);
    body.setPageToken(pageToken);
    final var resp = new ListChatResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  private static GetChatMembersResp membersPage(
      final boolean hasMore, final String pageToken, final String... memberIds) {
    final var body = new GetChatMembersRespBody();
    final var items = new ListMember[memberIds.length];
    for (int i = 0; i < memberIds.length; i++) {
      final var member = new ListMember();
      member.setMemberId(memberIds[i]);
      member.setMemberIdType("open_id");
      member.setName("Member " + i);
      items[i] = member;
    }
    body.setItems(items);
    body.setHasMore(hasMore);
    body.setPageToken(pageToken);
    body.setMemberTotal(memberIds.length);
    final var resp = new GetChatMembersResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  /** A page holding the run's own chat and one the asking user is not in. */
  private static ListChatResp twoChatsPage() {
    final var body = new ListChatRespBody();
    final var mine = new ListChat();
    mine.setChatId("oc_1");
    mine.setName("Release war room");
    final var theirs = new ListChat();
    theirs.setChatId("oc_2");
    theirs.setName("Somebody else's group");
    body.setItems(new ListChat[] {mine, theirs});
    body.setHasMore(false);
    final var resp = new ListChatResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  /** What the recall reads first, to learn whose conversation the message is in. */
  private static GetMessageResp messageIn(final String chatId) {
    final var found = new com.lark.oapi.service.im.v1.model.Message();
    found.setMessageId("om_1");
    found.setChatId(chatId);
    final var body = new GetMessageRespBody();
    body.setItems(new com.lark.oapi.service.im.v1.model.Message[] {found});
    final var resp = new GetMessageResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  @Test
  @DisplayName("listChats leaves out the groups the asking user is not in")
  void listChatsFiltersToTheAskersOwnGroups() throws Exception {
    when(chat.list(any())).thenReturn(twoChatsPage());
    // oc_1 is the run's own chat and needs no lookup; oc_2 is checked and they are not in it.
    when(chatMembers.get(any())).thenReturn(membersPage(false, null, "ou_someone"));

    final var page = tools.listChats(null, null, null, toolContext());

    assertThat(page.chats())
        .singleElement()
        .satisfies(it -> assertThat(it.chatId()).isEqualTo("oc_1"));
  }

  @Test
  @DisplayName("a chat the asking user is not in is refused before it is read")
  void getChatRefusesANonMember() throws Exception {
    when(chatMembers.get(any())).thenReturn(membersPage(false, null, "ou_someone"));

    assertThatThrownBy(() -> tools.getChat("oc_other", toolContext()))
        .isInstanceOf(ChatAccessDeniedException.class);
    verify(chat, never()).get(any());
  }

  @Test
  @DisplayName("the members of a chat the asking user is not in are refused, not returned")
  void listChatMembersRefusesANonMember() throws Exception {
    when(chatMembers.get(any())).thenReturn(membersPage(false, null, "ou_someone"));

    assertThatThrownBy(() -> tools.listChatMembers("oc_other", null, null, toolContext()))
        .isInstanceOf(ChatAccessDeniedException.class);
  }

  @Test
  @DisplayName("a recall reaching into a chat the asking user is not in is refused")
  void recallRefusesAMessageElsewhere() throws Exception {
    when(message.get(any())).thenReturn(messageIn("oc_elsewhere"));
    when(chatMembers.get(any())).thenReturn(membersPage(false, null, "ou_someone"));

    assertThatThrownBy(() -> tools.recallMessage("om_1", toolContext()))
        .isInstanceOf(ChatAccessDeniedException.class);
    verify(message, never()).delete(any());
  }

  @Test
  @DisplayName("listChats clamps a page size Feishu would reject outright")
  void listChatsClampsPageSize() throws Exception {
    when(chat.list(any())).thenReturn(chatsPage(false, null));

    tools.listChats(500, null, null, toolContext());

    final var captor = ArgumentCaptor.forClass(ListChatReq.class);
    verify(chat).list(captor.capture());
    // Not Feishu's 100: each chat on the page costs a membership check of its own.
    assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    // Its own default, rather than whatever Feishu's happens to be, so paging is repeatable.
    assertThat(captor.getValue().getSortType()).isEqualTo("ByCreateTimeAsc");
  }

  @Test
  @DisplayName("listChats hands back the page token so the model can carry on")
  void listChatsPaginates() throws Exception {
    when(chat.list(any())).thenReturn(chatsPage(true, "next-page"));

    final var page = tools.listChats(null, null, null, toolContext());

    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextPageToken()).isEqualTo("next-page");
    assertThat(page.chats())
        .singleElement()
        .satisfies(it -> assertThat(it.chatId()).isEqualTo("oc_1"));
  }

  @Test
  @DisplayName("a refused listing fails loudly rather than looking like an empty workspace")
  void listChatsFailureThrows() throws Exception {
    when(chat.list(any()))
        .thenReturn(failing(new ListChatResp(), 232025, "Bot ability is not activated."));

    assertThatThrownBy(() -> tools.listChats(null, null, null, toolContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Bot ability is not activated.");
  }

  @Test
  @DisplayName("getChat keeps the permission a mention of everybody depends on")
  void getChatCarriesAtAllPermission() throws Exception {
    final var body = new GetChatRespBody();
    body.setName("Release war room");
    body.setAtAllPermission("only_owner");
    body.setChatMode("group");
    body.setUserCount("42");
    body.setUserManagerIdList(new String[] {"ou_manager"});
    final var resp = new GetChatResp();
    resp.setData(body);
    resp.setCode(0);
    when(chat.get(any())).thenReturn(resp);

    final var info = tools.getChat("oc_1", toolContext());

    assertThat(info.chatId()).isEqualTo("oc_1");
    assertThat(info.atAllPermission()).isEqualTo("only_owner");
    assertThat(info.userCount()).isEqualTo("42");
    assertThat(info.userManagerIds()).containsExactly("ou_manager");
    // Absent in the response rather than empty, and an empty list is easier to reason about.
    assertThat(info.botManagerIds()).isEmpty();
  }

  @Test
  @DisplayName("isInChat with no user asks about the bot, which is the token's own identity")
  void isInChatAsksAboutTheBot() throws Exception {
    final var body = new IsInChatChatMembersRespBody();
    body.setIsInChat(true);
    final var resp = new IsInChatChatMembersResp();
    resp.setData(body);
    resp.setCode(0);
    when(chatMembers.isInChat(any())).thenReturn(resp);

    final var membership = tools.isInChat("oc_1", null, toolContext());

    assertThat(membership.member()).isTrue();
    assertThat(membership.subject()).isEqualTo("bot");
    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("isInChat about a person pages the member list until it finds them")
  void isInChatFindsAUserOnALaterPage() throws Exception {
    when(chatMembers.get(any()))
        .thenReturn(membersPage(true, "page-2", "ou_someone"))
        .thenReturn(membersPage(false, null, "ou_wanted"));

    final var membership = tools.isInChat("oc_1", "ou_wanted", toolContext());

    assertThat(membership.member()).isTrue();
    assertThat(membership.subject()).isEqualTo("ou_wanted");
    final var captor = ArgumentCaptor.forClass(GetChatMembersReq.class);
    verify(chatMembers, times(2)).get(captor.capture());
    assertThat(captor.getAllValues().get(1).getPageToken()).isEqualTo("page-2");
  }

  @Test
  @DisplayName("a user absent from a list that was read to the end is absent, and says so")
  void isInChatReportsAnAbsentUser() throws Exception {
    when(chatMembers.get(any())).thenReturn(membersPage(false, null, "ou_someone"));

    final var membership = tools.isInChat("oc_1", "ou_wanted", toolContext());

    assertThat(membership.member()).isFalse();
    assertThat(membership.note()).contains("read in full");
  }

  @Test
  @DisplayName("a member list that never ends stops, and says the answer is unknown rather than no")
  void isInChatStopsPagingSomewhere() throws Exception {
    // hasMore forever, as a chat far larger than anybody asks about would answer.
    when(chatMembers.get(any())).thenReturn(membersPage(true, "endless", "ou_someone"));

    final var membership = tools.isInChat("oc_1", "ou_wanted", toolContext());

    // Not false: a list that was never read to the end has not said they are absent.
    assertThat(membership.member()).isNull();
    assertThat(membership.note()).contains("Unknown, not no");
    verify(chatMembers, times(50)).get(any());
  }

  @Test
  @DisplayName("a recall Feishu refuses comes back as the reason, not as an exception")
  void recallFailureIsReported() throws Exception {
    when(message.get(any())).thenReturn(messageIn("oc_1"));
    when(message.delete(any()))
        .thenReturn(
            failing(new DeleteMessageResp(), 230026, "No permission to recall this message."));

    final var result = tools.recallMessage("om_1", toolContext());

    assertThat(result).contains("230026").contains("No permission to recall this message.");
  }

  @Test
  @DisplayName("a recall that worked says so")
  void recallSucceeds() throws Exception {
    final var resp = new DeleteMessageResp();
    resp.setCode(0);
    when(message.get(any())).thenReturn(messageIn("oc_1"));
    when(message.delete(any())).thenReturn(resp);

    assertThat(tools.recallMessage("om_1", toolContext())).contains("om_1").contains("recalled");
  }

  /**
   * The bot's own open_id, which is all of this record {@link FeishuChatAccess} reads; the rest is
   * credentials.
   */
  private static FeishuProperties botOpenId(final String openId) {
    return new FeishuProperties(null, null, null, null, null, openId, null, null, null, null, null);
  }
}
