package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.Client;
import com.lark.oapi.service.wiki.v2.model.GetNodeSpaceReq;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuWikiTools {

  private static final Pattern URL_TOKEN_PATTERN =
      Pattern.compile(
          "/(?<objType>wiki|doc|docx|sheets|base|mindnote|file|slides)/(?<token>[^/?#]+)");

  final Client feishu;

  @Builder
  @Jacksonized
  public static record WikiNodeInfo(
      String spaceId,
      String nodeToken,
      String objToken,
      String objType,
      String parentNodeToken,
      String nodeType,
      boolean hasChild,
      String title,
      String objCreateTime,
      String objEditTime,
      String nodeCreateTime,
      String creator,
      String owner,
      String nodeCreator) {}

  @Tool(
      name = "FeishuGetWikiNodeInfo",
      description =
          "Look up a Feishu wiki node: its title, node type, the id of the wiki space it belongs"
              + " to, and the token (objToken) and type (objType) of the cloud document behind it."
              + " Takes a wiki node link (https://xxx.feishu.cn/wiki/xxxxx), a document link"
              + " (.../docx/xxx, .../sheets/xxx), or a bare token.\n"
              + "**When objType comes back as sheet the node is a spreadsheet: pass the objToken as"
              + " spreadsheetToken to the sheet tools (FeishuListSheets, FeishuSheetReadRange,"
              + " FeishuSheetUpdateRange and the rest) to read or change it.** When it is docx or"
              + " doc, pass the objToken as documentId to the doc tools:"
              + " FeishuGetDocumentRawContent for the plain text, FeishuListDocBlocks and"
              + " FeishuGetDocBlockChildren for the block structure. When it is bitable, pass the"
              + " objToken as appToken to the bitable tools: FeishuListBitableTables for what it"
              + " holds and FeishuSearchBitableRecords for the rows of one of its tables.")
  @SneakyThrows
  public WikiNodeInfo getWikiNodeInfo(
      @ToolParam(
              description = "A wiki node link, a document link, or a bare node or document token")
          String urlOrToken,
      @ToolParam(
              description =
                  "Document type, required when urlOrToken is a document token rather than a wiki"
                      + " node token: one of doc, docx, sheet, mindnote, bitable, file, slides,"
                      + " wiki. Leave it out for a link or a wiki node token, which are recognised"
                      + " on their own and otherwise treated as wiki",
              required = false)
          String objType) {

    final var resolved = resolveTokenAndObjType(urlOrToken, objType);

    final var reqBuilder = GetNodeSpaceReq.newBuilder().token(resolved.token());
    if (resolved.objType() != null && !resolved.objType().isBlank()) {
      reqBuilder.objType(resolved.objType());
    }

    final var resp = feishu.wiki().v2().space().getNode(reqBuilder.build());
    if (!resp.success()) {
      log.error(
          "Failed to get wiki node info for '{}': {}, {}",
          urlOrToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get wiki node info: " + resp.getMsg());
    }

    final var node = resp.getData().getNode();
    log.info("Read wiki node info: token={}, objType={}", node.getNodeToken(), node.getObjType());
    return WikiNodeInfo.builder()
        .spaceId(node.getSpaceId())
        .nodeToken(node.getNodeToken())
        .objToken(node.getObjToken())
        .objType(node.getObjType())
        .parentNodeToken(node.getParentNodeToken())
        .nodeType(node.getNodeType())
        .hasChild(Boolean.TRUE.equals(node.getHasChild()))
        .title(node.getTitle())
        .objCreateTime(node.getObjCreateTime())
        .objEditTime(node.getObjEditTime())
        .nodeCreateTime(node.getNodeCreateTime())
        .creator(node.getCreator())
        .owner(node.getOwner())
        .nodeCreator(node.getNodeCreator())
        .build();
  }

  record ResolvedToken(String token, String objType) {}

  ResolvedToken resolveTokenAndObjType(final String urlOrToken, final String objType) {
    if (urlOrToken == null || urlOrToken.isBlank()) {
      throw new IllegalArgumentException("urlOrToken is required");
    }
    final var trimmed = urlOrToken.trim();

    final var matcher = URL_TOKEN_PATTERN.matcher(trimmed);
    if (!matcher.find()) {
      return new ResolvedToken(trimmed, objType);
    }
    final var urlObjType = matcher.group("objType");
    final var token = matcher.group("token");
    if (objType != null && !objType.isBlank()) {
      return new ResolvedToken(token, objType);
    }
    // The API's obj_type values don't match the URL path segments for every doc type.
    final var inferredObjType =
        switch (urlObjType) {
          case "sheets" -> "sheet";
          case "base" -> "bitable";
          default -> urlObjType;
        };
    return new ResolvedToken(token, inferredObjType);
  }
}
