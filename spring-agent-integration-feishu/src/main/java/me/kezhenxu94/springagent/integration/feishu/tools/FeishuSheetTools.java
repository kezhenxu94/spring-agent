package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.Client;
import com.lark.oapi.service.sheets.v3.model.CreateSpreadsheetReq;
import com.lark.oapi.service.sheets.v3.model.QuerySpreadsheetSheetReq;
import com.lark.oapi.service.sheets.v3.model.Spreadsheet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.ProtectedRange;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.Sheet;
import me.kezhenxu94.springagent.integration.feishu.sheet.FeishuSheetsService;
import me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange;
import me.kezhenxu94.springagent.integration.feishu.sheet.ValueRangeV2;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuSheetTools {

  final Client feishu;
  final FeishuSheetsService feishuSheetsService;
  final FeishuPermissionTools feishuPermissionTools;
  final JsonMapper objectMapper;

  @Builder
  @Jacksonized
  public static record CreatedSpreadsheet(
      String spreadsheetToken, String url, String defaultSheetId) {}

  @Builder
  @Jacksonized
  public static record RangeValues(String range, List<List<Object>> values) {}

  private static final String SHEET_DATA_FORMATS =
"""
飞书电子表格 v2 写入接口 (FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges) 支持的单元格数据类型：

1. 字符串：直接传字符串，例如 "文本"
2. 数字：直接传数字，例如 123
3. 日期：以数字形式写入，整数部分为自 1899-12-30 以来的天数，小数部分为当天时间占 24 小时的比例（例如 1900-01-01 中午 12 点为 2.5）；\
写入前需先调用 FeishuSheetSetRangeStyle 将目标单元格设置为日期格式（style 的 formatter 参数设为如 "yyyy/MM/dd"）
4. 无文本链接：直接传 URL 字符串，例如 "http://www.dd.com"
5. 带文本的链接：{"type": "url", "text": "文本", "link": "http://www.dd.com"}
6. 邮箱：直接传邮箱字符串，例如 "aaa@aa.com"
7. 公式：{"type": "formula", "text": "=A1"}（不支持跨表引用公式 IMPORTRANGE）
8. @人：{"type": "mention", "textType": "email", "text": "aaa@aa.com", "notify": true, "grantReadPermission": true}；\
textType 可选 email/openId/unionId；处理为异步，仅支持 @ 同租户用户，单次最多 @ 50 人
9. @文档：{"type": "mention", "textType": "fileToken", "text": "shtxxxx", "objType": "sheet"}；\
objType 可选 sheet/doc/slide/bitable/mindnote
10. 下拉列表：{"type": "multipleValue", "values": [1, "test"]}；values 元素可为 bool/string/number，\
string 不能包含逗号；写入前需先通过设置下拉列表接口配置好下拉选项（此工具集暂未提供）
11. 局部样式 segmentStyle（可附加在字符串/链接/邮箱/@人类型上，数字和下拉列表不支持）：\
{"bold": true, "italic": true, "strikeThrough": true, "underline": true, "foreColor": "#ff00ff", "fontSize": 20}；\
字符串类型示例：{"type": "text", "text": "string", "segmentStyle": {...}}

FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges 的 values 二维数组中，每个单元格可以是以上任意一种格式（纯字符串/数字，\
或符合上述结构的 JSON 对象）。

以上数字格式（如日期）依赖单元格本身的数字格式设置；字体、颜色、边框、对齐方式、数字格式等单元格样式（而非单元格内容）\
需要调用 FeishuSheetSetRangeStyle 单独设置，写入内容和设置样式是两个独立的操作。
""";

  @Builder
  @Jacksonized
  public static record SheetRangeValues(String range, List<List<Object>> values) {}

  @Tool(
      name = "FeishuCreateSpreadsheet",
      description =
          "在飞书云空间中创建新的电子表格，返回电子表格 token、URL 和默认工作表 ID。返回的 defaultSheetId 可直接用于后续"
              + " FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges / FeishuSheetReadRange /"
              + " FeishuSheetBatchReadRanges 等工具的区域参数，无需再调用 FeishuListSheets。使用场景: 当需要向用户返回一个列表"
              + " (例如文件列表、查询结果等), 且条目数 > 10 时, 不要直接在消息中罗列, 而是调用本工具创建新表格, 再用 FeishuSheetUpdateRange"
              + " 将数据写入该表格, 最后只把表格链接回复给用户。")
  @SneakyThrows
  public CreatedSpreadsheet createSpreadsheet(
      @ToolParam(description = "新电子表格的标题") String title,
      @ToolParam(description = "目标文件夹 token，留空使用默认文件夹", required = false) String folderToken,
      ToolContext toolContext) {

    final var targetFolderToken =
        folderToken == null || folderToken.isBlank()
            ? FeishuFileConstants.DEFAULT_FOLDER_TOKEN
            : folderToken;

    final var createdRes =
        feishu
            .sheets()
            .v3()
            .spreadsheet()
            .create(
                CreateSpreadsheetReq.newBuilder()
                    .spreadsheet(
                        Spreadsheet.newBuilder()
                            .title(title)
                            .folderToken(targetFolderToken)
                            .build())
                    .build());
    if (!createdRes.success()) {
      log.error(
          "Failed to create spreadsheet '{}': {}, {}",
          title,
          createdRes.getCode(),
          createdRes.getMsg());
      throw new IllegalStateException("Failed to create spreadsheet: " + createdRes.getMsg());
    }

    final var spreadsheetToken = createdRes.getData().getSpreadsheet().getSpreadsheetToken();
    final var url = createdRes.getData().getSpreadsheet().getUrl();

    final var sheetsRes =
        feishu
            .sheets()
            .v3()
            .spreadsheetSheet()
            .query(
                QuerySpreadsheetSheetReq.newBuilder().spreadsheetToken(spreadsheetToken).build());
    if (!sheetsRes.success()) {
      log.error(
          "Failed to query sheets of newly created spreadsheet {}: {}, {}",
          spreadsheetToken,
          sheetsRes.getCode(),
          sheetsRes.getMsg());
      throw new IllegalStateException("Failed to query sheets: " + sheetsRes.getMsg());
    }

    final var sheets = sheetsRes.getData().getSheets();
    if (sheets == null || sheets.length == 0) {
      throw new IllegalStateException("Created spreadsheet has no sheets");
    }

    final var defaultSheetId = sheets[0].getSheetId();
    log.info(
        "Created spreadsheet '{}': token={}, sheetId={}", title, spreadsheetToken, defaultSheetId);
    feishuPermissionTools.grantDefaultPermissions(toolContext, spreadsheetToken, "sheet");
    return CreatedSpreadsheet.builder()
        .spreadsheetToken(spreadsheetToken)
        .url(url)
        .defaultSheetId(defaultSheetId)
        .build();
  }

  @Tool(
      name = "FeishuListSheets",
      description =
          "获取电子表格中所有工作表及其属性信息（sheetId、标题、索引、是否隐藏、行列数、合并单元格等）。"
              + "在调用 FeishuSheetReadRange / FeishuSheetBatchReadRanges / FeishuSheetUpdateRange / "
              + "FeishuSheetBatchUpdateRanges 等需要 sheetId 的工具之前，若尚不知道目标工作表的 sheetId，"
              + "应先调用本工具获取。若持有的是飞书知识库 (wiki) 链接而非电子表格本身的链接/token，"
              + "无法直接确定其是否为电子表格，应先调用 FeishuGetWikiNodeInfo 获取该节点的 objType 与 "
              + "objToken：当 objType 为 sheet 时，再以 objToken 作为本工具及其它 FeishuSheetTools 工具的 "
              + "spreadsheetToken 参数。")
  public List<Sheet> listSheets(@ToolParam(description = "电子表格 token") String spreadsheetToken) {
    final var sheets = feishuSheetsService.getSheets(spreadsheetToken);
    log.info("Listed {} sheet(s) of spreadsheet {}", sheets.size(), spreadsheetToken);
    return sheets;
  }

  @Tool(
      name = "FeishuAddSheet",
      description =
          "在电子表格中新增一个工作表，返回新工作表的 sheetId、标题和索引位置；返回的 sheetId 可直接用于后续 FeishuSheetUpdateRange /"
              + " FeishuSheetBatchUpdateRanges / FeishuSheetReadRange 等工具，无需再调用"
              + " FeishuListSheets。本操作为结构性操作，无需事先调用 FeishuLockSheet 锁定。")
  public FeishuSheetsService.SheetSummary addSheet(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "新工作表的标题") String title,
      @ToolParam(description = "新工作表的位置索引，不填默认在第 0 索引位置插入", required = false) Integer index) {
    final var summary = feishuSheetsService.addSheet(spreadsheetToken, title, index);
    log.info(
        "Added sheet '{}' (sheetId={}) to spreadsheet {}",
        title,
        summary.sheetId(),
        spreadsheetToken);
    return summary;
  }

  @Tool(
      name = "FeishuCopySheet",
      description =
          "复制电子表格中的一个已有工作表，新工作表位于源工作表索引位置之后，返回新工作表的 sheetId、标题和索引位置；"
              + "返回的 sheetId 可直接用于后续 FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges / "
              + "FeishuSheetReadRange 等工具。本操作为结构性操作，无需事先调用 FeishuLockSheet 锁定。")
  public FeishuSheetsService.SheetSummary copySheet(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要复制的源工作表 ID，可通过 FeishuListSheets 获取") String sourceSheetId,
      @ToolParam(description = "新工作表的标题，不填默认为“源工作表名称(副本_索引)”，如 \"Sheet1(副本_0)\"", required = false)
          String newTitle) {
    final var summary = feishuSheetsService.copySheet(spreadsheetToken, sourceSheetId, newTitle);
    log.info(
        "Copied sheet {} to new sheet '{}' (sheetId={}) of spreadsheet {}",
        sourceSheetId,
        summary.title(),
        summary.sheetId(),
        spreadsheetToken);
    return summary;
  }

  @Tool(name = "FeishuDeleteSheet", description = "删除电子表格中的一个工作表，此操作不可撤销，删除前请与用户确认。")
  public String deleteSheet(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要删除的工作表 ID，可通过 FeishuListSheets 获取") String sheetId) {
    feishuSheetsService.deleteSheet(spreadsheetToken, sheetId);
    log.info("Deleted sheet {} of spreadsheet {}", sheetId, spreadsheetToken);
    return "已删除工作表 " + sheetId;
  }

  @Tool(
      name = "FeishuSheetReadRange",
      description =
          "读取电子表格中单个指定区域的数据；区域使用 A1 表示法并包含工作表 ID，例如 \"<sheetId>!A1:G5\"，"
              + "sheetId 可通过 FeishuListSheets 获取。若需一次读取多个区域，使用 FeishuSheetBatchReadRanges "
              + "以减少调用次数。")
  @SneakyThrows
  public SheetRangeValues readSheetRange(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "A1 表示法的区域，例如 \"<sheetId>!A1:G5\"") String range) {

    final var parsedRange = parseRange(range);
    final var result = feishuSheetsService.getRangeValuesV2(spreadsheetToken, parsedRange);
    final var valueRange = result.valueRange();
    log.info("Read range {} of spreadsheet {}", range, spreadsheetToken);
    return SheetRangeValues.builder()
        .range(valueRange.range().toString())
        .values(toPlainValues(valueRange.values()))
        .build();
  }

  @Tool(
      name = "FeishuSheetBatchReadRanges",
      description =
          "一次性读取电子表格中多个指定区域的数据，比多次调用 FeishuSheetReadRange 更高效；"
              + "区域使用 A1 表示法并包含工作表 ID，例如 \"<sheetId>!A1:G5\"，sheetId 可通过 FeishuListSheets 获取。")
  @SneakyThrows
  public List<SheetRangeValues> batchReadSheetRanges(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要读取的多个区域，每个区域为 A1 表示法，例如 \"<sheetId>!A1:G5\"")
          List<String> ranges) {

    if (ranges == null || ranges.isEmpty()) {
      return List.of();
    }

    final var parsedRanges = new ArrayList<ValueRange.Range>();
    for (final var range : ranges) {
      parsedRanges.add(parseRange(range));
    }

    final var result = feishuSheetsService.getRangeValuesBatchV2(spreadsheetToken, parsedRanges);
    log.info("Batch read {} range(s) of spreadsheet {}", ranges.size(), spreadsheetToken);
    return result.valueRanges().stream()
        .map(
            valueRange ->
                SheetRangeValues.builder()
                    .range(valueRange.range().toString())
                    .values(toPlainValues(valueRange.values()))
                    .build())
        .toList();
  }

  @Tool(
      name = "FeishuSheetUpdateRange",
      description =
          "向飞书电子表格的指定区域写入二维数组数据；区域使用 A1 表示法并包含工作表 ID，例如 \"<sheetId>!A1:G5\"。"
              + "**写入前必须先调用 FeishuLockSheet 锁定目标工作表，写入完成后必须调用 FeishuUnlockSheet 解锁**，"
              + "避免编辑期间与他人产生冲突；但如果同一工作表在本次任务中此前已经调用过 FeishuLockSheet 并已持有其 "
              + "protectId，则说明已处于锁定状态，无需重复锁定，直接写入即可。每个单元格可以是纯文本/数字，也可以是 "
              + "JSON 对象以支持公式、超链接、邮箱、@人、下拉列表等富文本类型；写入这些富文本类型前应先调用 "
              + "FeishuSheetDataFormats 获取支持的数据格式说明。若需写入多个区域，使用 FeishuSheetBatchUpdateRanges 更高效。")
  @SneakyThrows
  public String updateSheetRange(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "A1 表示法的区域，例如 \"<sheetId>!A1:G5\"") String range,
      @ToolParam(description = "按行存储的二维数组，每个单元格为纯文本/数字，或符合 FeishuSheetDataFormats 所述格式的 JSON 对象")
          List<List<Object>> values) {

    if (values == null || values.isEmpty()) {
      return "没有需要写入的数据。";
    }

    final var parsedRange = parseRange(range);
    final var cells = toCells(values);
    final var valueRange = ValueRangeV2.builder().range(parsedRange).values(cells).build();

    log.info(
        "Writing {} rows × {} columns to spreadsheet {}, range {}",
        cells.size(),
        cells.get(0).size(),
        spreadsheetToken,
        range);
    feishuSheetsService.setValuesV2(spreadsheetToken, valueRange);
    return "已写入 " + cells.size() + " 行至区域 " + range + "。";
  }

  @Tool(
      name = "FeishuSheetBatchUpdateRanges",
      description =
          "一次性向电子表格的多个区域写入数据，比多次调用 FeishuSheetUpdateRange 更高效；"
              + "区域使用 A1 表示法并包含工作表 ID，例如 \"<sheetId>!A1:G5\"。"
              + "**写入前必须先调用 FeishuLockSheet 锁定目标工作表，写入完成后必须调用 FeishuUnlockSheet 解锁**，"
              + "避免编辑期间与他人产生冲突；但如果同一工作表在本次任务中此前已经调用过 FeishuLockSheet 并已持有其 "
              + "protectId，则说明已处于锁定状态，无需重复锁定，直接写入即可。每个单元格可以是纯文本/数字，也可以是 "
              + "JSON 对象以支持公式、超链接、邮箱、@人、下拉列表等富文本类型；写入这些富文本类型前应先调用 "
              + "FeishuSheetDataFormats 获取支持的数据格式说明。")
  @SneakyThrows
  public String batchUpdateSheetRanges(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要写入的多个区域及对应数据") List<RangeValues> ranges) {

    if (ranges == null || ranges.isEmpty()) {
      return "没有需要写入的数据。";
    }

    final var valueRanges = new ArrayList<ValueRangeV2>();
    for (final var rangeValues : ranges) {
      final var parsedRange = parseRange(rangeValues.range());
      valueRanges.add(
          ValueRangeV2.builder().range(parsedRange).values(toCells(rangeValues.values())).build());
    }

    log.info("Writing {} range(s) to spreadsheet {}", valueRanges.size(), spreadsheetToken);
    feishuSheetsService.setValuesBatchV2(spreadsheetToken, valueRanges);
    return "已写入 " + valueRanges.size() + " 个区域的数据。";
  }

  @Tool(
      name = "FeishuSheetSetRangeStyle",
      description =
          "设置飞书电子表格指定区域的单元格样式（字体、颜色、边框、对齐方式、数字格式等），与写入单元格内容是两个独立的操作；"
              + "区域使用 A1 表示法并包含工作表 ID，例如 \"<sheetId>!A1:G5\"。**写入日期类型数据前，必须先调用本工具将目标区域的 "
              + "formatter 设置为对应的日期格式（例如 \"yyyy/MM/dd\"），再调用 FeishuSheetUpdateRange / "
              + "FeishuSheetBatchUpdateRanges 写入表示日期的数字**，否则单元格会显示为普通数字。"
              + "**设置前必须先调用 FeishuLockSheet 锁定目标工作表，设置完成后必须调用 FeishuUnlockSheet 解锁**，"
              + "避免编辑期间与他人产生冲突；但如果同一工作表在本次任务中此前已经调用过 FeishuLockSheet 并已持有其 "
              + "protectId，则无需重复锁定。单次设置范围不超过 5000 行 100 列，设置边框样式时单次不超过 30000 个单元格。")
  @SneakyThrows
  public String setRangeStyle(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "A1 表示法的区域，例如 \"<sheetId>!A1:G5\"") String range,
      @ToolParam(description = "是否加粗", required = false) Boolean bold,
      @ToolParam(description = "是否斜体", required = false) Boolean italic,
      @ToolParam(
              description = "字体大小或行距，如 \"10pt\"（字号，范围 9~36pt）或 \"1.5\"（行距，固定为 1.5px）",
              required = false)
          String fontSize,
      @ToolParam(description = "文本装饰，可选值: 0=默认(无) 1=下划线 2=删除线 3=下划线+删除线", required = false)
          Integer textDecoration,
      @ToolParam(
              description = "数字/日期格式，例如 \"yyyy/MM/dd\" 表示日期、\"0.00%\" 表示百分比；写入日期数据前必须先设置此项",
              required = false)
          String formatter,
      @ToolParam(description = "水平对齐方式，可选值: 0=左对齐 1=居中 2=右对齐", required = false) Integer hAlign,
      @ToolParam(description = "垂直对齐方式，可选值: 0=上对齐 1=居中 2=下对齐", required = false) Integer vAlign,
      @ToolParam(description = "字体颜色，十六进制颜色代码，例如 \"#000000\"", required = false) String foreColor,
      @ToolParam(description = "背景颜色，十六进制颜色代码，例如 \"#21D11F\"", required = false) String backColor,
      @ToolParam(
              description =
                  "边框类型，可选值: FULL_BORDER(全边框) OUTER_BORDER(外边框) INNER_BORDER(内边框) "
                      + "NO_BORDER(无边框) LEFT_BORDER RIGHT_BORDER TOP_BORDER BOTTOM_BORDER",
              required = false)
          String borderType,
      @ToolParam(description = "边框颜色，十六进制颜色代码，例如 \"#FF0000\"", required = false) String borderColor,
      @ToolParam(description = "是否清除该区域的所有已有样式，默认为 false", required = false) Boolean clean) {

    final var style = new LinkedHashMap<String, Object>();
    if (bold != null || italic != null || fontSize != null) {
      final var font = new LinkedHashMap<String, Object>();
      if (bold != null) {
        font.put("bold", bold);
      }
      if (italic != null) {
        font.put("italic", italic);
      }
      if (fontSize != null) {
        font.put("fontSize", fontSize);
      }
      style.put("font", font);
    }
    if (textDecoration != null) {
      style.put("textDecoration", textDecoration);
    }
    if (formatter != null) {
      style.put("formatter", formatter);
    }
    if (hAlign != null) {
      style.put("hAlign", hAlign);
    }
    if (vAlign != null) {
      style.put("vAlign", vAlign);
    }
    if (foreColor != null) {
      style.put("foreColor", foreColor);
    }
    if (backColor != null) {
      style.put("backColor", backColor);
    }
    if (borderType != null) {
      style.put("borderType", borderType);
    }
    if (borderColor != null) {
      style.put("borderColor", borderColor);
    }
    if (clean != null) {
      style.put("clean", clean);
    }

    if (style.isEmpty()) {
      return "没有需要设置的样式。";
    }

    final var parsedRange = parseRange(range);
    log.info("Setting style {} on range {} of spreadsheet {}", style, range, spreadsheetToken);
    feishuSheetsService.setStyle(spreadsheetToken, parsedRange, style);
    return "已为区域 " + range + " 设置样式。";
  }

  @Tool(
      name = "FeishuSheetDataFormats",
      description =
          "获取 FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges 支持写入的单元格数据类型及对应 JSON 格式说明"
              + "（字符串、数字、日期、链接、邮箱、@人、公式、@文档、下拉列表、局部样式等）。"
              + "在写入纯文本/数字以外的富文本类型之前，应先调用本工具了解对应的 JSON 结构。")
  public String getSheetDataFormats() {
    return SHEET_DATA_FORMATS;
  }

  @Tool(
      name = "FeishuLockSheet",
      description =
          "锁定飞书电子表格中的指定工作表，防止编辑期间他人修改；在通过 FeishuSheetUpdateRange 或 "
              + "FeishuSheetBatchUpdateRanges 修改表格前调用，操作完成后必须调用 FeishuUnlockSheet 解锁。"
              + "**锁定成功后会返回 protectId，请在本次任务中记住它：如果同一个工作表在本次任务中已经调用过本工具并拿到了 "
              + "protectId，说明该工作表已处于锁定状态，不要重复调用本工具再次锁定（重复锁定没有意义，还会拖慢执行速度），"
              + "直接使用该锁进行后续写入即可，仅需在全部写入完成后调用一次 FeishuUnlockSheet。若不确定此前的锁是否仍然有效，"
              + "应调用 FeishuGetProtectedRanges 并传入已知的 protectId 查询当前锁定状态，而不是直接重新锁定。**")
  public String lockSheet(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要锁定的工作表 ID") String sheetId,
      @ToolParam(description = "锁定备注信息，例如锁定原因", required = false) String lockInfo) {
    final var protectId = feishuSheetsService.lockSheet(spreadsheetToken, sheetId, lockInfo);
    log.info(
        "Locked sheet {} of spreadsheet {}, protectId={}", sheetId, spreadsheetToken, protectId);
    return protectId == null || protectId.isBlank()
        ? "已锁定工作表 " + sheetId
        : "已锁定工作表 " + sheetId + "，protectId=" + protectId + "（请记住此 protectId，同一工作表无需重复锁定）";
  }

  @Tool(
      name = "FeishuUnlockSheet",
      description =
          "解锁飞书电子表格中此前通过 FeishuLockSheet 锁定的工作表；通常在 FeishuSheetUpdateRange 或 "
              + "FeishuSheetBatchUpdateRanges 写入完成后调用。同一工作表在本次任务中只需解锁一次，"
              + "不要在每次写入后都调用，应等所有写入操作全部完成后再调用一次。")
  public String unlockSheet(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要解锁的工作表 ID") String sheetId) {
    feishuSheetsService.unlockSheet(spreadsheetToken, sheetId);
    log.info("Unlocked sheet {} of spreadsheet {}", sheetId, spreadsheetToken);
    return "已解锁工作表 " + sheetId;
  }

  @Tool(
      name = "FeishuGetProtectedRanges",
      description =
          "获取电子表格中指定保护范围 (protectId) 的详细信息，包括锁定的行列范围、备注信息 (lockInfo) 及可编辑该范围的用户列表；"
              + "单次最多查询 5 个 protectId。protectId 来自此前调用 FeishuLockSheet 时返回的值。"
              + "用于在需要再次写入同一工作表前，确认此前持有的锁是否仍然有效，从而避免重复调用 FeishuLockSheet 造成的冲突和无谓重试。")
  public List<ProtectedRange> getProtectedRanges(
      @ToolParam(description = "电子表格 token") String spreadsheetToken,
      @ToolParam(description = "要查询的保护范围 protectId 列表，最多 5 个") List<String> protectIds,
      @ToolParam(
              description = "返回的用户 ID 类型，可选 userId(默认)/openId/unionId，建议使用 openId",
              required = false)
          String memberType) {
    if (protectIds == null || protectIds.isEmpty()) {
      return List.of();
    }
    if (protectIds.size() > 5) {
      throw new IllegalArgumentException("protectIds 最多支持 5 个，实际传入 " + protectIds.size() + " 个");
    }

    final var ranges =
        feishuSheetsService.getProtectedRanges(spreadsheetToken, protectIds, memberType);
    log.info("Got {} protected range(s) of spreadsheet {}", ranges.size(), spreadsheetToken);
    return ranges;
  }

  ValueRange.Range parseRange(final String range) throws Exception {
    return objectMapper.readValue(objectMapper.writeValueAsString(range), ValueRange.Range.class);
  }

  List<List<JsonNode>> toCells(final List<List<Object>> values) {
    final var cells = new ArrayList<List<JsonNode>>();
    for (final var row : values) {
      final var rowNodes = new ArrayList<JsonNode>();
      for (final var cell : row) {
        rowNodes.add(cell == null ? StringNode.valueOf("") : objectMapper.valueToTree(cell));
      }
      cells.add(rowNodes);
    }
    return cells;
  }

  List<List<Object>> toPlainValues(final List<List<JsonNode>> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(
            row ->
                row.stream()
                    .map(cell -> (Object) objectMapper.convertValue(cell, Object.class))
                    .toList())
        .toList();
  }
}
