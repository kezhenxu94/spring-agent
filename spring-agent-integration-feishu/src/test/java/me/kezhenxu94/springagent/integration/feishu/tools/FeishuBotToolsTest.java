package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.V1;
import com.lark.oapi.service.im.v1.model.GetChatMembersReq;
import com.lark.oapi.service.im.v1.model.GetChatMembersResp;
import com.lark.oapi.service.im.v1.model.GetChatMembersRespBody;
import com.lark.oapi.service.im.v1.model.ListMember;
import com.lark.oapi.service.im.v1.resource.ChatMembers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuBotToolsTest {

  @Mock private Client feishu;
  @Mock private ImService im;
  @Mock private V1 v1;
  @Mock private ChatMembers chatMembers;

  private FeishuBotTools tools;

  /**
   * The bot's own open_id and the language its refusals are written in, which is all of this record
   * the chat access check reads; the rest is credentials.
   */
  private static final FeishuProperties FEISHU =
      new FeishuProperties(
          null, null, null, null, null, "ou_bot", null, Locale.ENGLISH, null, null, null);

  @BeforeEach
  void setUp() {
    when(feishu.im()).thenReturn(im);
    when(im.v1()).thenReturn(v1);
    when(v1.chatMembers()).thenReturn(chatMembers);
    tools =
        new FeishuBotTools(
            feishu,
            new JsonMapper(),
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
                FEISHU,
                new FeishuMessages(FEISHU)));
  }

  private static RawResponse raw(final String body) {
    final var response = new RawResponse();
    response.setStatusCode(200);
    response.setBody(body.getBytes(StandardCharsets.UTF_8));
    return response;
  }

  /** A complete member list of a chat, which is what a membership check is answered from. */
  private static GetChatMembersResp membersPage(final String... memberIds) {
    final var body = new GetChatMembersRespBody();
    final var items = new ListMember[memberIds.length];
    for (int i = 0; i < memberIds.length; i++) {
      final var member = new ListMember();
      member.setMemberId(memberIds[i]);
      member.setMemberIdType("open_id");
      items[i] = member;
    }
    body.setItems(items);
    body.setHasMore(false);
    body.setMemberTotal(memberIds.length);
    final var resp = new GetChatMembersResp();
    resp.setData(body);
    resp.setCode(0);
    return resp;
  }

  /** The chat the run is in, which is the one the asking user needs no lookup to be allowed. */
  private static ToolContext toolContext() {
    return new ToolContext(
        Map.of(ToolContexts.KEY_USER_ID, "ou_asking", ToolContexts.KEY_CHAT_ID, "oc_1"));
  }

  @Test
  @DisplayName("the bot's own info is read from the top-level bot object, not from data")
  void readsItsOwnInfo() throws Exception {
    when(feishu.get(eq("/open-apis/bot/v3/info"), any(), eq(AccessTokenType.Tenant)))
        .thenReturn(
            raw(
                """
                {"code":0,"msg":"ok","bot":{"activate_status":2,"app_name":"Agent",
                 "avatar_url":"https://example.com/a.png","ip_white_list":["1.2.3.4"],
                 "open_id":"ou_bot"}}
                """));

    final var info = tools.getBotInfo();

    assertThat(info.appName()).isEqualTo("Agent");
    assertThat(info.openId()).isEqualTo("ou_bot");
    assertThat(info.avatarUrl()).isEqualTo("https://example.com/a.png");
    assertThat(info.activateStatus()).isEqualTo(2);
    assertThat(info.activateStatusText())
        .as("a bare 2 says nothing to a model about whether the app is usable")
        .isEqualTo("enabled");
    assertThat(info.ipWhiteList()).containsExactly("1.2.3.4");
  }

  @Test
  @DisplayName("a non-zero code is raised rather than answered with an empty bot")
  void failsLoudlyOnItsOwnInfo() throws Exception {
    when(feishu.get(eq("/open-apis/bot/v3/info"), any(), eq(AccessTokenType.Tenant)))
        .thenReturn(raw("{\"code\":99991663,\"msg\":\"bot ability is off\"}"));

    assertThatThrownBy(() -> tools.getBotInfo())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bot ability is off");
  }

  @Test
  @DisplayName("a search flattens meta_data and reports the page")
  void searchesBots() throws Exception {
    when(feishu.post(any(), any(), eq(AccessTokenType.Tenant)))
        .thenReturn(
            raw(
                """
                {"code":0,"msg":"success","data":{"items":[{"id":"7890",
                 "display_info":"飞书<h>搜索</h>助手","meta_data":{"tenant_id":"701",
                 "enable_join_group":false,"chat_id":"oc_2","is_agent":true}}],
                 "has_more":true,"page_token":"next","notice":"truncated"}}
                """));

    final var page = tools.searchBots("助手", null, null, null, null, toolContext());

    assertThat(page.bots()).hasSize(1);
    assertThat(page.bots().getFirst().id()).isEqualTo("7890");
    assertThat(page.bots().getFirst().displayInfo()).isEqualTo("飞书<h>搜索</h>助手");
    assertThat(page.bots().getFirst().tenantId()).isEqualTo("701");
    assertThat(page.bots().getFirst().chatId()).isEqualTo("oc_2");
    assertThat(page.bots().getFirst().enableJoinGroup()).isFalse();
    assertThat(page.bots().getFirst().agent()).isTrue();
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextPageToken()).isEqualTo("next");
    assertThat(page.notice()).isEqualTo("truncated");
  }

  @Test
  @DisplayName("a query longer than Feishu accepts is trimmed rather than rejected by Feishu")
  void trimsAnOverlongQuery() throws Exception {
    when(feishu.post(any(), any(), eq(AccessTokenType.Tenant)))
        .thenReturn(raw("{\"code\":0,\"data\":{\"items\":[]}}"));

    tools.searchBots("x".repeat(80), null, null, null, null, toolContext());

    final var body = ArgumentCaptor.forClass(Object.class);
    verify(feishu).post(any(), body.capture(), eq(AccessTokenType.Tenant));
    assertThat(((Map<?, ?>) body.getValue()).get("query")).isEqualTo("x".repeat(50));
  }

  @Test
  @DisplayName("naming somebody else's chat is refused before anything is searched")
  void refusesAChatTheAskerIsNotIn() throws Exception {
    when(chatMembers.get(any(GetChatMembersReq.class))).thenReturn(membersPage("ou_somebody_else"));

    assertThatThrownBy(
            () -> tools.searchBots(null, List.of("oc_other"), null, null, null, toolContext()))
        .isInstanceOf(ChatAccessDeniedException.class);

    verify(feishu, never()).post(any(), any(), any());
  }

  @Test
  @DisplayName("a refusal by Feishu says the search could not be run, not that nothing matched")
  void reportsARefusalAsARefusal() throws Exception {
    when(feishu.post(any(), any(), eq(AccessTokenType.Tenant)))
        .thenReturn(raw("{\"code\":99991672,\"msg\":\"no permission\"}"));

    assertThatThrownBy(() -> tools.searchBots("a", null, null, null, null, toolContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be run");
  }
}
