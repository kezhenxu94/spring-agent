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
          "获取飞书知识空间节点（Wiki）的信息，包括标题、节点类型、所属知识空间 ID、以及该节点对应的实际云文档 token (objToken) 及类型"
              + " (objType)。支持直接传入知识库节点链接 (如 https://xxx.feishu.cn/wiki/xxxxx) 或云文档链接 (如"
              + " .../docx/xxx、.../sheets/xxx)，也可以直接传入 token。**返回的 objType 为 sheet"
              + " 时，说明该节点是一个电子表格，此时应使用返回的 objToken 作为 spreadsheetToken，调用 FeishuSheetTools 中的工具（如"
              + " FeishuListSheets、FeishuSheetReadRange、FeishuSheetUpdateRange 等）读取或修改其内容**；objType"
              + " 为 docx/doc 时可使用 objToken 作为 documentId，调用 FeishuDocTools 中的工具（如"
              + " FeishuGetDocumentRawContent 读取纯文本内容，FeishuListDocBlocks、FeishuGetDocBlockChildren"
              + " 等读取或编辑块级结构）。")
  @SneakyThrows
  public WikiNodeInfo getWikiNodeInfo(
      @ToolParam(description = "知识库节点链接、云文档链接或者节点/文档的 token") String urlOrToken,
      @ToolParam(
              description =
                  "文档类型，当 urlOrToken 为云文档 token（非知识库节点 token）时必须传入，"
                      + "可选值: doc, docx, sheet, mindnote, bitable, file, slides, wiki；"
                      + "若 urlOrToken 是链接或知识库节点 token，可留空，将自动识别或默认按 wiki 处理",
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
