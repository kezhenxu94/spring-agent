package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.V1;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.GetChatMembersRespBody;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersResp;
import com.lark.oapi.service.im.v1.model.IsInChatChatMembersRespBody;
import com.lark.oapi.service.im.v1.model.ListMember;
import com.lark.oapi.service.im.v1.resource.ChatMembers;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The one place that decides whether a chat may be read, and the only tests that can tell a refusal
 * from an empty answer before a user finds out for themselves.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuChatAccessTest {

  @Mock private Client feishu;
  @Mock private ImService im;
  @Mock private V1 v1;
  @Mock private ChatMembers chatMembers;

  /** What {@code app.feishu.bot-open-id} names: the identity an unattended run is given. */
  private static final String BOT = "ou_bot";

  private FeishuChatAccess access;

  @BeforeEach
  void setUp() {
    when(feishu.im()).thenReturn(im);
    when(im.v1()).thenReturn(v1);
    when(v1.chatMembers()).thenReturn(chatMembers);
    access = accessWithAdmins(Set.of());
  }

  private FeishuChatAccess accessWithAdmins(final Set<String> admins) {
    return accessWith(admins, BOT);
  }

  private FeishuChatAccess accessWith(final Set<String> admins, final String botOpenId) {
    return new FeishuChatAccess(
        feishu,
        new Admins(
            new SpringAgentProperties(
                null,
                new SpringAgentProperties.Ai(admins, Map.of(), null, null, null, null, null, null),
                Locale.ENGLISH,
                null,
                null)),
        // The bot's own open_id is the only part of that record this class reads.
        new FeishuProperties(
            null, null, null, null, null, botOpenId, null, null, null, null, null));
  }

  private static ToolContext context(final String userId, final String chatId) {
    return new ToolContext(
        Map.of(ToolContexts.KEY_USER_ID, userId, ToolContexts.KEY_CHAT_ID, chatId));
  }

  private static GetChatMembersResp page(
      final boolean hasMore, final String pageToken, final String... memberIds) {
    final var body = new GetChatMembersRespBody();
    final var items = new ListMember[memberIds.length];
    for (int i = 0; i < memberIds.length; i++) {
      items[i] = new ListMember();
      items[i].setMemberId(memberIds[i]);
    }
    body.setItems(items);
    body.setHasMore(hasMore);
    body.setPageToken(pageToken);
    final var resp = new GetChatMembersResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  @Test
  @DisplayName("the chat this run is in needs no lookup: the message came from it")
  void theRunsOwnChatIsFree() throws Exception {
    access.requireMember(context("ou_1", "oc_current"), "oc_current");

    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("another chat is allowed once the person is found in its member list")
  void anotherChatIsAllowedForAMember() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(false, null, "ou_someone", "ou_1"));

    access.requireMember(context("ou_1", "oc_current"), "oc_other");

    verify(chatMembers).get(any());
  }

  @Test
  @DisplayName("a chat the person is not in is refused, not answered")
  void aChatTheyAreNotInIsRefused() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(false, null, "ou_someone"));

    assertThatThrownBy(() -> access.requireMember(context("ou_1", "oc_current"), "oc_other"))
        .isInstanceOf(ChatAccessDeniedException.class)
        .hasMessageContaining("you are not in chat oc_other");
  }

  @Test
  @DisplayName("a member list that cannot be read to the end is refused, since unknown is not yes")
  void anUnreadableListFailsClosed() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(true, "more", "ou_someone"));

    assertThatThrownBy(() -> access.requireMember(context("ou_1", "oc_current"), "oc_huge"))
        .isInstanceOf(ChatAccessDeniedException.class)
        .hasMessageContaining("could not be established");
    verify(chatMembers, org.mockito.Mockito.times(FeishuChatAccess.MAX_MEMBER_PAGES)).get(any());
  }

  @Test
  @DisplayName("a chat the bot itself is not in reads as refused rather than as an error")
  void aChatTheBotIsNotInIsRefused() throws Exception {
    final var refused = new GetChatMembersResp();
    refused.setCode(232011);
    refused.setMsg("Operator can NOT be out of the chat.");
    when(chatMembers.get(any())).thenReturn(refused);

    assertThatThrownBy(() -> access.requireMember(context("ou_1", "oc_current"), "oc_other"))
        .isInstanceOf(ChatAccessDeniedException.class);
  }

  @Test
  @DisplayName("a run with nobody behind it has nobody to authorise, so it is refused")
  void aRunWithNoUserIsRefused() throws Exception {
    assertThatThrownBy(() -> access.requireMember(new ToolContext(Map.of()), "oc_other"))
        .isInstanceOf(IllegalStateException.class);
    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("an admin is let through, since an operator asks about the bot itself")
  void anAdminIsLetThrough() throws Exception {
    accessWithAdmins(Set.of("ou_admin")).requireMember(context("ou_admin", "oc_current"), "oc_any");

    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("filtering a listing leaves out what it cannot confirm, and never throws for it")
  void filteringLeavesOutTheUnconfirmable() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(true, "more", "ou_someone"));

    assertThat(access.isMemberForFiltering(context("ou_1", "oc_current"), "oc_huge")).isFalse();
    // The tighter bound: a listing pays this per chat, so it stops far sooner than a direct check.
    verify(chatMembers, org.mockito.Mockito.times(FeishuChatAccess.MAX_MEMBER_PAGES_WHEN_FILTERING))
        .get(any());
  }

  private static IsInChatChatMembersResp isInChat(final boolean member) {
    final var body = new IsInChatChatMembersRespBody();
    body.setIsInChat(member);
    final var resp = new IsInChatChatMembersResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  @Test
  @DisplayName("the bot's own identity is asked of is_in_chat, since no member list ever holds it")
  void theBotIsAnsweredByIsInChat() throws Exception {
    when(chatMembers.isInChat(any())).thenReturn(isInChat(true));

    access.requireMember(context(BOT, "oc_current"), "oc_other");

    verify(chatMembers).isInChat(any());
    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("and is still bounded by it: a chat the bot is not in is refused as anybody's is")
  void theBotIsRefusedAChatItIsNotIn() throws Exception {
    when(chatMembers.isInChat(any())).thenReturn(isInChat(false));

    assertThatThrownBy(() -> access.requireMember(context(BOT, "oc_current"), "oc_other"))
        .isInstanceOf(ChatAccessDeniedException.class)
        // Said as the bot's own problem, since an unattended run has no "you" to be told about.
        .hasMessageContaining("this bot is not in chat oc_other")
        .hasMessageContaining("added to that group");
    verify(chatMembers, never()).get(any());
  }

  @Test
  @DisplayName("an unanswerable is_in_chat is unknown, and unknown is refused for the bot too")
  void theBotFailsClosed() throws Exception {
    final var refused = new IsInChatChatMembersResp();
    refused.setCode(232011);
    refused.setMsg("Operator can NOT be out of the chat.");
    when(chatMembers.isInChat(any())).thenReturn(refused);

    assertThatThrownBy(() -> access.requireMember(context(BOT, "oc_current"), "oc_other"))
        .isInstanceOf(ChatAccessDeniedException.class)
        .hasMessageContaining("could not be established");
  }

  @Test
  @DisplayName("nobody else gets the bot's answer: a person is still read from the member list")
  void aPersonIsStillWalked() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(false, null, "ou_someone"));

    assertThatThrownBy(() -> access.requireMember(context("ou_1", "oc_current"), "oc_other"))
        .isInstanceOf(ChatAccessDeniedException.class);
    verify(chatMembers, never()).isInChat(any());
  }

  @Test
  @DisplayName("a deployment that named no bot open_id matches nobody, blank user ids included")
  void anUnconfiguredBotOpenIdMatchesNobody() throws Exception {
    when(chatMembers.get(any())).thenReturn(page(false, null, "ou_someone"));

    assertThatThrownBy(
            () -> accessWith(Set.of(), null).requireMember(context("ou_1", "oc_current"), "oc_o"))
        .isInstanceOf(ChatAccessDeniedException.class);
    verify(chatMembers, never()).isInChat(any());
  }
}
