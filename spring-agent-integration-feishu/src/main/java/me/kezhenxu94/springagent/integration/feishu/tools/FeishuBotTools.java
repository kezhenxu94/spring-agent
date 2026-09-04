package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who the bot itself is, and which other bots the workspace has.
 *
 * <p>Both endpoints go through the SDK's raw request rather than a generated resource, because the
 * bot service is not in the SDK at all — 2.6.1 ships no {@code com.lark.oapi.service.bot} package,
 * so there is nothing to call. Raw means the reply is parsed here: {@code /bot/v3/info} puts the
 * bot under a top-level {@code bot} rather than under {@code data}, which is why {@link
 * me.kezhenxu94.springagent.integration.feishu.model.FeishuResponse} does not fit it.
 *
 * <p>{@link #searchBots} is documented as taking a {@code user_access_token} and this application
 * holds only the app's tenant token, so it can come back refused; the refusal is reported as what
 * it is rather than as an empty result, so that the model says "I could not search" instead of
 * "there are no such bots".
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuBotTools {

  /** What a caller gets when it names no page size, matching the rest of this module. */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /** Feishu's own ceiling on the search endpoint's query. */
  private static final int MAX_QUERY_LENGTH = 50;

  /**
   * What {@code activate_status} means, spelled out. The endpoint answers with a bare integer, and
   * a model told "2" has no way to know that is the only value meaning the app is usable.
   */
  private static final Map<Integer, String> ACTIVATE_STATUS =
      Map.of(
          0, "initialized, waiting for the tenant to install it",
          1, "disabled by the tenant",
          2, "enabled",
          3, "installed, waiting to be enabled",
          4, "upgraded, waiting to be enabled",
          5, "disabled because the licence expired",
          6, "disabled because the Lark plan expired or was downgraded");

  final Client feishu;
  final JsonMapper objectMapper;
  final FeishuChatAccess access;

  @Builder
  @Jacksonized
  public static record BotInfo(
      String appName,
      String openId,
      String avatarUrl,
      Integer activateStatus,
      String activateStatusText,
      List<String> ipWhiteList) {}

  @Builder
  @Jacksonized
  public static record BotSearchItem(
      String id,
      String displayInfo,
      String tenantId,
      String chatId,
      Boolean enableJoinGroup,
      Boolean agent) {}

  @Builder
  @Jacksonized
  public static record BotSearchPage(
      List<BotSearchItem> bots, String nextPageToken, boolean hasMore, String notice) {}

  @Tool(
      name = "FeishuGetBotInfo",
      description =
          "Read this bot's own identity in Feishu: its app name, its open_id, its avatar and"
              + " whether the workspace has it enabled. Use it when the user asks who you are,"
              + " what you are called here, or what your open_id is — the open_id is the one to"
              + " mention the bot with, and the one to look for when checking whether the bot is"
              + " already in a group's member list. Nothing here is about the person asking; it is"
              + " the identity every Feishu call this application makes is authorised as.")
  @SneakyThrows
  public BotInfo getBotInfo() {
    final var raw = feishu.get("/open-apis/bot/v3/info", null, AccessTokenType.Tenant);
    final var body = read(raw);
    final var code = body.path("code").asInt(-1);
    if (code != 0) {
      log.error("Failed to read the bot's own info: code={}, msg={}", code, body.path("msg"));
      throw new IllegalStateException(
          "Failed to read the bot's own info: " + code + " " + body.path("msg").asString(""));
    }
    final var bot = body.path("bot");
    final var status =
        bot.hasNonNull("activate_status") ? bot.get("activate_status").asInt() : null;
    log.info("Read the bot's own info: {}", bot.path("app_name").asString(""));
    return BotInfo.builder()
        .appName(text(bot, "app_name"))
        .openId(text(bot, "open_id"))
        .avatarUrl(text(bot, "avatar_url"))
        .activateStatus(status)
        .activateStatusText(status == null ? null : ACTIVATE_STATUS.get(status))
        .ipWhiteList(strings(bot.path("ip_white_list")))
        .build();
  }

  @Tool(
      name = "FeishuSearchBots",
      description =
          "Search the Feishu workspace for other bots by keyword: their id, the matched text of"
              + " their description, whether they may be added to groups and whether they are an"
              + " agent. Use it to answer which bot does a thing, or which bots are in a group."
              + " This is about other bots — use FeishuGetBotInfo for this bot itself. Feishu"
              + " documents this endpoint as needing a signed-in person's token, while this"
              + " application only ever holds the app's own, so it can come back refused: report"
              + " that it could not be searched rather than that there are no such bots. Naming"
              + " chatIds only works for groups the person you are talking to is in.")
  @SneakyThrows
  public BotSearchPage searchBots(
      @ToolParam(description = "The keyword to search for, at most 50 characters", required = false)
          final String query,
      @ToolParam(
              description =
                  "Limit the search to the bots in these groups, as chat_ids of the form oc_xxx",
              required = false)
          final List<String> chatIds,
      @ToolParam(
              description = "True to return only bots this workspace has already chatted with",
              required = false)
          final Boolean hasChatter,
      @ToolParam(description = "How many to return; 20 by default", required = false)
          final Integer pageSize,
      @ToolParam(
              description = "The nextPageToken of a previous call, to read the page after it",
              required = false)
          final String pageToken,
      final ToolContext toolContext) {

    // A chat_id here is a way of asking what is in somebody else's group, so it is checked exactly
    // as the chat tools check one — see FeishuChatAccess for why the bot's own token cannot.
    if (chatIds != null) {
      chatIds.forEach(chatId -> access.requireMember(toolContext, chatId));
    }

    final var filter = new HashMap<String, Object>();
    if (chatIds != null && !chatIds.isEmpty()) {
      filter.put("chat_ids", chatIds);
    }
    if (hasChatter != null) {
      filter.put("has_chatter", hasChatter);
    }
    final var request = new HashMap<String, Object>();
    if (!Strings.isNullOrEmpty(query)) {
      // Feishu truncates a longer query and says so in `notice`; trimming here keeps that notice
      // for things worth reporting.
      request.put(
          "query",
          query.length() > MAX_QUERY_LENGTH ? query.substring(0, MAX_QUERY_LENGTH) : query);
    }
    if (!filter.isEmpty()) {
      request.put("filter", filter);
    }

    final var path =
        "/open-apis/bot/v4/bot/search?user_id_type=open_id&page_size="
            + (pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(pageSize, 1))
            + (Strings.isNullOrEmpty(pageToken) ? "" : "&page_token=" + pageToken);
    final var raw = feishu.post(path, request, AccessTokenType.Tenant);
    final var body = read(raw);
    final var code = body.path("code").asInt(-1);
    if (code != 0) {
      log.error("Failed to search bots: code={}, msg={}", code, body.path("msg"));
      throw new IllegalStateException(
          "Failed to search bots: "
              + code
              + " "
              + body.path("msg").asString("")
              + ". This endpoint wants a signed-in person's token, which this application does not"
              + " hold, so say the search could not be run rather than that nothing matched.");
    }

    final var data = body.path("data");
    final var bots = new ArrayList<BotSearchItem>();
    data.path("items")
        .forEach(
            item -> {
              final var meta = item.path("meta_data");
              bots.add(
                  BotSearchItem.builder()
                      .id(text(item, "id"))
                      .displayInfo(text(item, "display_info"))
                      .tenantId(text(meta, "tenant_id"))
                      .chatId(text(meta, "chat_id"))
                      .enableJoinGroup(bool(meta, "enable_join_group"))
                      .agent(bool(meta, "is_agent"))
                      .build());
            });
    log.info("Searched bots for '{}', {} on this page", query, bots.size());
    return BotSearchPage.builder()
        .bots(bots)
        .nextPageToken(text(data, "page_token"))
        .hasMore(data.path("has_more").asBoolean(false))
        .notice(text(data, "notice"))
        .build();
  }

  private JsonNode read(final RawResponse raw) {
    return objectMapper.readTree(new String(raw.getBody(), StandardCharsets.UTF_8));
  }

  private static String text(final JsonNode node, final String field) {
    return node.hasNonNull(field) ? node.get(field).asString() : null;
  }

  private static Boolean bool(final JsonNode node, final String field) {
    return node.hasNonNull(field) ? node.get(field).asBoolean() : null;
  }

  private static List<String> strings(final JsonNode node) {
    if (!node.isArray()) {
      return List.of();
    }
    final var values = new ArrayList<String>();
    node.forEach(value -> values.add(value.asString()));
    return List.copyOf(values);
  }
}
