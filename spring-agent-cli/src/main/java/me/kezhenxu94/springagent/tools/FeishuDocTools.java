package me.kezhenxu94.springagent.tools;

import java.io.File;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.bot.feishu.FeishuProperties;
import me.kezhenxu94.springagent.bot.feishu.docx.FeishuDocxService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuDocTools {
  final FeishuDocxService feishuDocxService;
  final JsonMapper objectMapper;
  final FeishuProperties feishuProperties;
  final FeishuPermissionTools feishuPermissionTools;

  @Builder
  @Jacksonized
  public static record CreatedDocument(
      String documentId, Integer revisionId, String title, String url) {}

  @Builder
  @Jacksonized
  public static record DocumentInfo(String documentId, Integer revisionId, String title) {}

  private static final String DOC_BLOCK_GUIDE =
"""
飞书文档 (docx) 块级操作参考。

一、block_type 常用取值（每个块的 JSON 对象都包含 block_id、block_type、parent_id、children 等公共字段，\
并附带与 block_type 对应的类型字段，例如 text/heading1/table 等）：

1  Page（文档根块，即 documentId 本身）    2  Text 文本    3~11  Heading1~Heading9 标题
12 Bullet 无序列表   13 Ordered 有序列表   14 Code 代码块   15 Quote 引用
17 Todo 待办事项      18 Bitable 多维表格   19 Callout 高亮块
21 Diagram 流程图/UML  22 Divider 分割线（内容为空对象 {}）
23 File 文件（须与 33 View 搭配出现）      24 Grid 分栏          25 GridColumn 分栏列
27 Image 图片         30 Sheet 电子表格     31 Table 表格
32 TableCell 表格单元格   33 View 视图（承载 File/Sheet 等的展示形态）
34 QuoteContainer 引用容器（内容为空对象 {}）
其余类型（ChatCard/MindNote/Board/OKR/Task/SourceSynced/ReferenceSynced 等，多为只读或暂不支持通过本工具集创建）\
详见 FeishuDocBlockContentReference。

二、通用参数说明：
- documentRevisionId：乐观并发控制的文档版本号，写入类工具建议传 -1 表示基于最新版本操作；\
可通过 FeishuGetDocumentInfo 获取当前最新的 revisionId。
- clientToken：幂等键，建议每次调用生成一个新的 UUID 并传入，避免网络重试导致重复写入。
- GridColumn、TableCell、Callout 类型的块在创建时必须至少包含一个子块（哪怕是空的 Text 块），不能完全为空。
- 各类块的内容实体（Image/Table/Grid/Callout/File/Sheet 等）具体 JSON 字段，参见 FeishuDocBlockContentReference。

三、核心工作流建议（**重要**）：
新建一篇文档并写入正文内容时，**不要**使用 FeishuCreateDocBlockChildren 手动逐块拼装 JSON，\
正确做法是：
1. 调用 FeishuCreateDocument 创建空文档，得到 documentId（新文档已自带一个 Page 根块，无需再创建）；
2. 调用 FeishuConvertMarkdownOrHtmlToBlocks 将 Markdown 或 HTML 正文转换为块结构，\
得到 firstLevelBlockIds 和 blocks（一棵以临时 ID 表示父子关系的块树）；
3. 将上一步返回的 blocks 原样作为 descendantsJson、firstLevelBlockIds 作为 childrenId，\
调用 FeishuCreateDocBlockDescendant，一次性将整棵块树插入 documentId 的根块下（blockId 传 documentId 本身）；
4. 若内容中包含表格，插入前需去掉每个 table 块 property 中的 mergeInfo 字段（只读字段，携带会报错）；
5. 若内容中包含图片/文件，按下文「四、图片与附件插入工作流」完成上传和替换；
6. 单次 FeishuCreateDocBlockDescendant 最多插入 1000 个块，超出需分批调用。

FeishuCreateDocBlockChildren 仅适用于在已有内容基础上追加少量（不超过 50 个、不含子块层级的）扁平同级块，\
例如在文档末尾补充几行文字。

四、图片与附件插入工作流：
1. 得到目标 Image/File 块的真实 block_id：通过 FeishuConvertMarkdownOrHtmlToBlocks + \
FeishuCreateDocBlockDescendant 插入后，从返回的 blockIdRelations 中查找；或直接调用 \
FeishuCreateDocBlockChildren 单独创建一个空 Image/File 块获得其 block_id（File 块创建后会自动生成一个父级 \
View 块，属正常现象）；
2. 调用 FeishuUploadDocBlockMedia，以该 block_id 作为 parent_node 上传本地文件（图片传 parentType=\
docx_image，文件传 parentType=docx_file），得到 fileToken；
3. 调用 FeishuUpdateDocBlock，对该 block_id 执行 replaceImage（图片）或 replaceFile（文件）操作，\
将 fileToken 写入 token 字段，完成替换。

五、杂项提示：
- 更新文档标题：documentId 与 blockId 均传文档 Token（即 Page 根块 ID），调用 FeishuUpdateDocBlock 执行 \
updateTextElements 操作写入新标题。
- 写入类接口存在限频（如更新单个块约 3 次/秒），批量修改多个块时优先使用 FeishuBatchUpdateDocBlocks 而非\
循环调用 FeishuUpdateDocBlock。
- 电子表格（Sheet）块创建后仅获得空表格，往单元格中写入数据需使用电子表格相关工具（如 FeishuSheetTools），\
本工具集不直接提供 Sheet 单元格读写能力。
""";

  private static final String DOC_BLOCK_CONTENT_REFERENCE =
"""
飞书文档 (docx) 块内容实体（BlockData）结构参考，用于手工拼装 childrenJson / descendantsJson / \
updateOperationJson / requestsJson 中与 block_type 对应的类型字段。仅在 \
FeishuConvertMarkdownOrHtmlToBlocks 无法覆盖的场景（例如需要精细控制图片尺寸、合并单元格、分栏比例等）才\
需要手工拼装；常规正文内容优先走 Markdown/HTML 转换。

一、Image（block_type=27）：
{"token": "(只读，由 FeishuUploadDocBlockMedia 上传后通过 replaceImage 写入)", "width": int, \
"height": int, "align": 1|2|3（居左/居中/居右）, "caption": {"content": "图片描述文本"}}

二、Table（block_type=31）与 TableCell（block_type=32）：
Table 内容为 {"property": {"row_size": int, "column_size": int, "column_width": [int...], \
"header_row": boolean, "header_column": boolean}}，children 为若干 TableCell 的 block_id；\
TableCell 内容为空对象 {}，其 children 可承载任意其它块（文本、列表等）。\
**注意**：property 中的 merge_info 为只读字段，创建/插入时必须去除，如需合并单元格需在创建后\
通过 FeishuUpdateDocBlock 的 mergeTableCells 操作完成。

三、Grid（block_type=24）与 GridColumn（block_type=25）：
Grid 内容为 {"column_size": int}（取值 2~5），children 为对应数量的 GridColumn block_id；\
GridColumn 内容为 {"width_ratio": int}（1~99，各列之和建议为 100），children 至少包含一个块。

四、Callout（block_type=19，高亮块）：
{"background_color": enum, "border_color": enum, "text_color": enum, "emoji_id": "表情名，如 gift"}，\
children 至少包含一个块（例如一个 Text 块）。

五、File（block_type=23）+ View（block_type=33）：
File 块不能独立存在，必须由一个 View 块（{"view_type": 1}，卡片视图）作为其父块；\
File 内容为 {"token": "(只读，创建时留空，由 replaceFile 写入)", "name": "文件名", "view_type": 1|2}。

六、Sheet（block_type=30，电子表格）：
创建时仅指定 {"row_size": int（最大 9）, "column_size": int（最大 9）}，token 为只读字段；\
写入单元格内容需改用电子表格相关工具，不在本工具集范围内。

七、文本元素（Text 类型块 elements 数组中的特殊元素）：
- @提及用户：{"mention_user": {"user_id": "用户 OpenID"}}（不会触发系统通知）；
- 公式：{"equation": {"content": "符合 KaTeX 语法的公式内容"}}。

八、只读或暂不支持创建的类型（了解即可，本工具集不提供创建/编辑能力）：
Bitable 多维表格、Diagram 流程图/UML、MindNote 思维笔记、Board 画板、Task 任务、OKR 及其子块、\
SourceSynced/ReferenceSynced 同步块——这些块只能通过 FeishuGetDocBlock / FeishuListDocBlocks 读取，\
无法通过本工具集创建。
""";

  @Tool(
      name = "FeishuCreateDocument",
      description =
          "创建一个新的飞书文档（docx），仅创建空文档，不含正文内容。返回的 documentId 可用于本工具集其它工具，"
              + "返回的 url 是该文档的链接，创建完成（如需写入正文内容则在写入完成后）应将该链接回复给用户。"
              + "**创建后如需写入正文内容，不要用 FeishuCreateDocBlockChildren 手动逐块拼装，"
              + "应优先调用 FeishuConvertMarkdownOrHtmlToBlocks 将 Markdown/HTML 转换为块结构，"
              + "再调用 FeishuCreateDocBlockDescendant 一次性插入**，详见 FeishuDocBlockGuide。"
              + "若需基于已有文档（模板）创建新文档，应使用云空间的复制文件接口，本工具集暂未提供。")
  @SneakyThrows
  public CreatedDocument createDocument(
      @ToolParam(description = "文档标题") String title,
      @ToolParam(description = "目标文件夹 token，留空使用默认文件夹", required = false) String folderToken,
      ToolContext toolContext) {
    final var targetFolderToken =
        folderToken == null || folderToken.isBlank()
            ? FeishuFileConstants.DEFAULT_FOLDER_TOKEN
            : folderToken;
    final var document = feishuDocxService.createDocument(targetFolderToken, title);
    feishuPermissionTools.grantDefaultPermissions(toolContext, document.getDocumentId(), "docx");
    return CreatedDocument.builder()
        .documentId(document.getDocumentId())
        .revisionId(document.getRevisionId())
        .title(document.getTitle())
        .url("https://" + feishuProperties.tenantDomain() + "/docx/" + document.getDocumentId())
        .build();
  }

  @Tool(
      name = "FeishuGetDocumentInfo",
      description =
          "获取飞书文档的标题及最新版本号 revisionId。revisionId 用于后续写入类工具（如 FeishuCreateDocBlockChildren、"
              + "FeishuUpdateDocBlock 等）的 documentRevisionId 参数，做乐观并发控制；也可直接传 -1 表示使用最新版本，"
              + "无需先调用本工具获取。")
  public DocumentInfo getDocumentInfo(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId) {
    final var document = feishuDocxService.getDocumentInfo(documentId);
    return DocumentInfo.builder()
        .documentId(document.getDocumentId())
        .revisionId(document.getRevisionId())
        .title(document.getTitle())
        .build();
  }

  @Tool(name = "FeishuGetDocumentRawContent", description = "获取飞书文档的纯文本内容。")
  public String getDocumentRawContent(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId) {
    return feishuDocxService.getDocumentRawContent(documentId);
  }

  @Tool(
      name = "FeishuListDocBlocks",
      description =
          "分页获取飞书文档中的全部块（扁平列表，不体现层级关系，层级由每个块的 parent_id/children 字段表达）。"
              + "这是获取文档中各个 block_id 的主要方式，后续调用 FeishuGetDocBlockChildren、FeishuGetDocBlock、"
              + "FeishuUpdateDocBlock、FeishuDeleteDocBlockChildren 等工具前通常需要先通过本工具或 "
              + "FeishuGetDocBlockChildren 定位目标 block_id。")
  @SneakyThrows
  public JsonNode listDocBlocks(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "分页标记，翻页时传入上一次返回的 pageToken", required = false) String pageToken,
      @ToolParam(description = "每页数量，默认且最大 500", required = false) Integer pageSize) {
    final var json =
        feishuDocxService.listDocumentBlocks(documentId, documentRevisionId, pageToken, pageSize);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuGetDocBlockChildren",
      description =
          "获取指定块的子块列表；withDescendants 为 true 时返回该块及其所有后代的完整前序遍历树，"
              + "为 false（默认）时仅返回直接子块。block_id 可通过 FeishuListDocBlocks 获取；"
              + "若要获取文档根块的直接子块，将 blockId 传入 documentId 本身即可。")
  @SneakyThrows
  public JsonNode getDocBlockChildren(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "父块的 block_id，传 documentId 本身表示文档根块") String blockId,
      @ToolParam(description = "是否返回所有后代块（完整子树），默认 false 仅返回直接子块", required = false)
          Boolean withDescendants,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "分页标记，翻页时传入上一次返回的 pageToken", required = false) String pageToken,
      @ToolParam(description = "每页数量，默认且最大 500", required = false) Integer pageSize) {
    final var json =
        feishuDocxService.getDocumentBlockChildren(
            documentId, blockId, withDescendants, documentRevisionId, pageToken, pageSize);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuGetDocBlock",
      description = "获取单个块的详细内容；block_id 可通过 FeishuListDocBlocks 或 FeishuGetDocBlockChildren 获取。")
  @SneakyThrows
  public JsonNode getDocBlock(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "块的 block_id") String blockId,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId) {
    final var json = feishuDocxService.getDocumentBlock(documentId, blockId, documentRevisionId);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuCreateDocBlockChildren",
      description =
          "在指定父块下创建一批扁平的同级子块（不支持在同一次调用中携带子块的子块，最多 50 个块，其中 Sheet 类型的块最多 5 个）。"
              + "**仅用于在已有内容基础上追加少量扁平内容（例如在文档末尾补几行文字）；"
              + "如果是从零构建一整篇文档的正文内容，不要用本工具手动逐块拼装，"
              + "应改用 FeishuConvertMarkdownOrHtmlToBlocks + FeishuCreateDocBlockDescendant 的组合**，"
              + "详见 FeishuDocBlockGuide。childrenJson 的块结构说明参见 FeishuDocBlockContentReference。")
  @SneakyThrows
  public JsonNode createDocBlockChildren(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "父块的 block_id，传 documentId 本身表示插入到文档根块下") String blockId,
      @ToolParam(description = "要创建的子块数组 JSON 字符串，每个元素为一个块对象，结构参见 FeishuDocBlockContentReference")
          String childrenJson,
      @ToolParam(description = "插入位置索引，默认 -1 表示追加到末尾，0 表示插入到最前面", required = false) Integer index,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "幂等键，建议传入一个 UUID，避免重试导致重复创建", required = false) String clientToken) {
    final var json =
        feishuDocxService.createDocumentBlockChildren(
            documentId, blockId, childrenJson, index, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuCreateDocBlockDescendant",
      description =
          "**推荐的正文内容写入方式**：一次性插入一整棵带层级关系的块树（最多 1000 个块），适合插入从 FeishuConvertMarkdownOrHtmlToBlocks"
              + " 转换出的内容，或表格、分栏等含子块层级的结构。descendantsJson 中每个块使用调用方自定义的临时 block_id 表达父子关系（children"
              + " 字段为其它临时 ID 的数组），childrenId 是这棵树中作为父块 blockId 直接子节点的临时 ID 列表（通常直接使用转换接口返回的"
              + " firstLevelBlockIds）。返回结果中的 blockIdRelations 记录了临时 ID 与插入后真实 block_id"
              + " 的映射：**若内容包含图片或附件，需按 FeishuDocBlockGuide 中「图片与附件插入工作流」，用其中的真实 block_id 依次调用"
              + " FeishuUploadDocBlockMedia 和 FeishuUpdateDocBlock"
              + " 完成替换**。GridColumn/TableCell/Callout 类型的块必须至少包含一个子块。工作流详见"
              + " FeishuDocBlockGuide，块结构详见 FeishuDocBlockContentReference。")
  @SneakyThrows
  public JsonNode createDocBlockDescendant(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "父块的 block_id，传 documentId 本身表示插入到文档根块下") String blockId,
      @ToolParam(description = "作为父块直接子节点的临时 block_id 列表，通常直接使用转换接口返回的 firstLevelBlockIds")
          List<String> childrenId,
      @ToolParam(description = "带层级关系的块树数组 JSON 字符串，通常直接使用转换接口返回的 blocks") String descendantsJson,
      @ToolParam(description = "插入位置索引，默认 -1 表示追加到末尾，0 表示插入到最前面", required = false) Integer index,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "幂等键，建议传入一个 UUID，避免重试导致重复创建", required = false) String clientToken) {
    if (childrenId == null) {
      throw new IllegalArgumentException("childrenId must not be null");
    }
    final var json =
        feishuDocxService.createDocumentBlockDescendant(
            documentId,
            blockId,
            childrenId.toArray(new String[0]),
            descendantsJson,
            index,
            documentRevisionId,
            clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuUpdateDocBlock",
      description =
          "对单个块执行一项更新操作（更新文本内容/段落样式、表格行列增删/合并、分栏列增删/宽度、替换图片/附件、更新待办状态等）。"
              + "updateOperationJson 必须且只能包含其中一个操作字段，例如 "
              + "{\"updateTextElements\": {...}} 或 {\"replaceImage\": {...}}，具体每种操作的字段结构参见 "
              + "FeishuDocBlockContentReference 及飞书开放平台文档；replaceImage/replaceFile 所需的 token "
              + "通过先调用 FeishuUploadDocBlockMedia 获取。若需对多个块批量执行更新操作，使用 "
              + "FeishuBatchUpdateDocBlocks 更高效。")
  @SneakyThrows
  public JsonNode updateDocBlock(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "要更新的块的 block_id") String blockId,
      @ToolParam(description = "更新操作 JSON 字符串，必须且只能包含一个操作字段") String updateOperationJson,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "幂等键，建议传入一个 UUID，避免重试导致重复更新", required = false) String clientToken) {
    final var json =
        feishuDocxService.patchDocumentBlock(
            documentId, blockId, updateOperationJson, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuBatchUpdateDocBlocks",
      description =
          "一次性对文档中的多个块执行更新操作（最多 200 个，且同一次调用中不能对同一个 block_id 重复下发操作），"
              + "比多次调用 FeishuUpdateDocBlock 更高效。requestsJson 为数组，每个元素既包含目标 blockId，"
              + "又包含且只能包含一个更新操作字段，结构参见 FeishuDocBlockContentReference。")
  @SneakyThrows
  public JsonNode batchUpdateDocBlocks(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "更新请求数组 JSON 字符串，每个元素包含 blockId 及一个更新操作字段") String requestsJson,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "幂等键，建议传入一个 UUID，避免重试导致重复更新", required = false) String clientToken) {
    final var json =
        feishuDocxService.batchUpdateDocumentBlocks(
            documentId, requestsJson, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuDeleteDocBlockChildren",
      description =
          "删除父块下连续一段区间 [startIndex, endIndex) 的子块（按索引，startIndex 从 0"
              + " 开始，区间左闭右开）。**不支持**用本工具删除表格的行/列或分栏的列，那些需改用 FeishuUpdateDocBlock 的 deleteTableRows"
              + " / deleteTableColumns / deleteGridColumn 操作；也不支持将 TableCell/GridColumn/Callout"
              + " 的子块全部删空。block_id 可通过 FeishuListDocBlocks 获取。")
  public String deleteDocBlockChildren(
      @ToolParam(description = "飞书文档的唯一标识 document_id") String documentId,
      @ToolParam(description = "父块的 block_id") String blockId,
      @ToolParam(description = "起始索引（含），从 0 开始") int startIndex,
      @ToolParam(description = "结束索引（不含）") int endIndex,
      @ToolParam(description = "文档版本号，默认 -1 表示最新版本", required = false) Integer documentRevisionId,
      @ToolParam(description = "幂等键，建议传入一个 UUID，避免重试导致重复删除", required = false) String clientToken) {
    feishuDocxService.deleteDocumentBlockChildren(
        documentId, blockId, startIndex, endIndex, documentRevisionId, clientToken);
    return "已删除区间 [" + startIndex + ", " + endIndex + ") 内的子块。";
  }

  @Tool(
      name = "FeishuConvertMarkdownOrHtmlToBlocks",
      description =
          "将 Markdown 或 HTML 文本转换为飞书文档块结构（不会写入任何文档，仅做纯转换），"
              + "支持文本、H1~H9 标题、无序/有序列表、代码块、引用、待办、图片、表格及表格单元格。"
              + "**这是从零构建新文档正文内容的推荐入口**：转换得到 firstLevelBlockIds 和 blocks 后，"
              + "将 blocks 原样作为 descendantsJson、firstLevelBlockIds 作为 childrenId，"
              + "直接调用 FeishuCreateDocBlockDescendant 整体插入目标文档（blockId 传目标 documentId 本身即可）。"
              + "注意：若转换结果包含表格，插入前需先去掉每个 table 块 property 中的 mergeInfo 字段（只读字段）；"
              + "若包含图片，返回结果中的 blockIdToImageUrls 给出了每个图片临时块对应的临时图片地址，"
              + "插入后应按 FeishuDocBlockGuide 中「图片与附件插入工作流」调用 FeishuUploadDocBlockMedia 和 "
              + "FeishuUpdateDocBlock 完成替换；若 blocks 数量超过 1000，需拆分为多次 "
              + "FeishuCreateDocBlockDescendant 调用。")
  @SneakyThrows
  public JsonNode convertMarkdownOrHtmlToBlocks(
      @ToolParam(description = "内容类型，可选值: markdown / html") String contentType,
      @ToolParam(description = "要转换的 Markdown 或 HTML 文本内容") String content) {
    final var json = feishuDocxService.convertToBlocks(contentType, content);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuDocBlockGuide",
      description =
          "获取飞书文档块级操作的工作流参考说明，包括常用 block_type"
              + " 取值、通用参数（documentRevisionId/clientToken）说明、新建文档正文内容的推荐工作流（优先用"
              + " FeishuConvertMarkdownOrHtmlToBlocks + FeishuCreateDocBlockDescendant，而非手动用"
              + " FeishuCreateDocBlockChildren 逐块拼装）、图片与附件的上传替换工作流，以及标题更新、限频等杂项提示。"
              + "在插入图片/附件、更新文档标题或批量更新块之前，应先调用本工具了解流程；"
              + "若需要具体某个 block_type 的 JSON 字段结构，改用 FeishuDocBlockContentReference。")
  public String getDocBlockGuide() {
    return DOC_BLOCK_GUIDE;
  }

  @Tool(
      name = "FeishuDocBlockContentReference",
      description =
          "获取飞书文档各 block_type 对应内容实体（BlockData）的 JSON 字段结构参考（Image/Table/Grid/Callout/"
              + "File/Sheet 及 @提及用户、公式等文本元素），用于手工拼装 childrenJson / descendantsJson / "
              + "updateOperationJson / requestsJson 中与 block_type 对应的类型字段。仅在 "
              + "FeishuConvertMarkdownOrHtmlToBlocks 无法覆盖的场景（精细控制图片尺寸、合并单元格、分栏比例等）才"
              + "需要手工拼装；工作流层面的建议参见 FeishuDocBlockGuide。")
  public String getDocBlockContentReference() {
    return DOC_BLOCK_CONTENT_REFERENCE;
  }

  @Tool(
      name = "FeishuUploadDocBlockMedia",
      description =
          "上传本地图片/文件素材并绑定到文档中已存在的 Image/File 块，是插入图片或附件工作流的第二步"
              + "（第一步：得到目标 Image/File 块的真实 block_id；第三步：调用 FeishuUpdateDocBlock 执行 "
              + "replaceImage/replaceFile 操作，将本工具返回的 fileToken 写入 token 字段完成替换）。"
              + "详细工作流参见 FeishuDocBlockGuide 中「图片与附件插入工作流」。")
  @SneakyThrows
  public String uploadDocBlockMedia(
      @ToolParam(description = "目标 Image 或 File 块的真实 block_id，将作为上传接口的 parent_node") String blockId,
      @ToolParam(description = "本地文件的绝对路径") String filePath,
      @ToolParam(description = "素材文件名，用于展示") String fileName,
      @ToolParam(description = "素材类型，图片传 docx_image，文件/附件传 docx_file") String parentType) {
    final var file = new File(filePath);
    if (!file.isFile()) {
      throw new IllegalArgumentException(
          "filePath does not point to an existing file: " + filePath);
    }
    return feishuDocxService.uploadMedia(fileName, parentType, blockId, file);
  }
}
