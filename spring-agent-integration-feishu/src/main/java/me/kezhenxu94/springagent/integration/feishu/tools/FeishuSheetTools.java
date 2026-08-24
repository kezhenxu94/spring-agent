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
Cell types the Feishu spreadsheet v2 write endpoints (FeishuSheetUpdateRange /
FeishuSheetBatchUpdateRanges) accept:

1. String: the string itself, e.g. "some text"
2. Number: the number itself, e.g. 123
3. Date: written as a number — whole part is days since 1899-12-30, fractional part is the time as a \
share of 24 hours (1900-01-01 at noon is 2.5). Set the target cells to a date format with \
FeishuSheetSetRangeStyle first (style's formatter parameter, e.g. "yyyy/MM/dd")
4. Bare link: the URL string itself, e.g. "http://www.dd.com"
5. Link with text: {"type": "url", "text": "some text", "link": "http://www.dd.com"}
6. Email: the address itself, e.g. "aaa@aa.com"
7. Formula: {"type": "formula", "text": "=A1"} (IMPORTRANGE, which reaches across spreadsheets, is \
not supported)
8. Mention a person: {"type": "mention", "textType": "email", "text": "aaa@aa.com", "notify": true, \
"grantReadPermission": true}. textType is email, openId or unionId. Handled asynchronously, only \
for users of the same tenant, at most 50 per write
9. Mention a document: {"type": "mention", "textType": "fileToken", "text": "shtxxxx", "objType": \
"sheet"}. objType is sheet, doc, slide, bitable or mindnote
10. Dropdown: {"type": "multipleValue", "values": [1, "test"]}. Values are booleans, strings or \
numbers, and a string cannot contain a comma. The options themselves have to be configured through \
the dropdown endpoint, which these tools do not cover yet
11. Inline styling with segmentStyle, which strings, links, emails and mentions accept but numbers \
and dropdowns do not: \
{"bold": true, "italic": true, "strikeThrough": true, "underline": true, "foreColor": "#ff00ff", "fontSize": 20}. \
On a string that reads {"type": "text", "text": "string", "segmentStyle": {...}}

Every cell of the values array passed to FeishuSheetUpdateRange or FeishuSheetBatchUpdateRanges can \
be any of the above: a bare string or number, or a JSON object of one of these shapes.

The numeric formats above, dates among them, depend on the cell's own number format. Cell styling \
rather than cell content — font, colour, borders, alignment, number format — is set separately with \
FeishuSheetSetRangeStyle: writing content and styling it are two different operations.
""";

  @Builder
  @Jacksonized
  public static record SheetRangeValues(String range, List<List<Object>> values) {}

  @Tool(
      name = "FeishuCreateSpreadsheet",
      description =
          "Create a spreadsheet in Feishu drive and return its token, URL and default sheet id. The"
              + " defaultSheetId can go straight into the range parameter of"
              + " FeishuSheetUpdateRange, FeishuSheetBatchUpdateRanges, FeishuSheetReadRange or"
              + " FeishuSheetBatchReadRanges, with no call to FeishuListSheets in between.\n"
              + "Use it whenever a list owed to the user runs past ten rows, a file listing or"
              + " query result say: rather than spelling them out in the reply, create a"
              + " spreadsheet, write the rows with FeishuSheetUpdateRange, and reply with nothing"
              + " but the link.\n"
              + "A spreadsheet is a grid of cells. When the rows have structure the user will want"
              + " to query later — a status to filter on, an owner to group by, a date to sort by —"
              + " FeishuCreateBitable gives them typed columns and views instead, and is the better"
              + " choice.")
  @SneakyThrows
  public CreatedSpreadsheet createSpreadsheet(
      @ToolParam(description = "Title of the new spreadsheet") String title,
      @ToolParam(
              description = "Token of the folder to create it in; the default folder when left out",
              required = false)
          String folderToken,
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
          "List every sheet of a spreadsheet with its properties: sheetId, title, index, whether it"
              + " is hidden, its row and column counts, its merged cells. Call it first whenever a"
              + " tool needs a sheetId that is not known yet, which FeishuSheetReadRange,"
              + " FeishuSheetBatchReadRanges, FeishuSheetUpdateRange and"
              + " FeishuSheetBatchUpdateRanges all do.\n"
              + "A wiki link says nothing about whether it leads to a spreadsheet: for one of"
              + " those, call FeishuGetWikiNodeInfo first, and if the objType it returns is sheet,"
              + " pass its objToken as the spreadsheetToken here and to the other sheet tools.")
  public List<Sheet> listSheets(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken) {
    final var sheets = feishuSheetsService.getSheets(spreadsheetToken);
    log.info("Listed {} sheet(s) of spreadsheet {}", sheets.size(), spreadsheetToken);
    return sheets;
  }

  @Tool(
      name = "FeishuAddSheet",
      description =
          "Add a sheet to a spreadsheet and return its sheetId, title and index. The sheetId can"
              + " go straight into FeishuSheetUpdateRange, FeishuSheetBatchUpdateRanges or"
              + " FeishuSheetReadRange, with no call to FeishuListSheets in between. Being a change"
              + " of structure rather than content, it needs no FeishuLockSheet first.")
  public FeishuSheetsService.SheetSummary addSheet(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "Title of the new sheet") String title,
      @ToolParam(
              description = "Where to put it; inserted at index 0 when left out",
              required = false)
          Integer index) {
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
          "Copy a sheet of a spreadsheet, placing the copy just after the original, and return its"
              + " sheetId, title and index. The sheetId can go straight into"
              + " FeishuSheetUpdateRange, FeishuSheetBatchUpdateRanges or FeishuSheetReadRange."
              + " Being a change of structure rather than content, it needs no FeishuLockSheet"
              + " first.")
  public FeishuSheetsService.SheetSummary copySheet(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "Id of the sheet to copy, as returned by FeishuListSheets")
          String sourceSheetId,
      @ToolParam(
              description =
                  "Title of the copy. Left out, Feishu names it after the original with a"
                      + " suffix of its own",
              required = false)
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

  @Tool(
      name = "FeishuDeleteSheet",
      description =
          "Delete a sheet from a spreadsheet. This cannot be undone, so confirm with the user"
              + " first.")
  public String deleteSheet(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "Id of the sheet to delete, as returned by FeishuListSheets")
          String sheetId) {
    feishuSheetsService.deleteSheet(spreadsheetToken, sheetId);
    log.info("Deleted sheet {} of spreadsheet {}", sheetId, spreadsheetToken);
    return "Deleted sheet " + sheetId + ".";
  }

  @Tool(
      name = "FeishuSheetReadRange",
      description =
          "Read one range of a spreadsheet. Ranges are A1 notation including the sheet id, as in"
              + " \"<sheetId>!A1:G5\", and FeishuListSheets returns the sheet id. For several"
              + " ranges at once use FeishuSheetBatchReadRanges instead of calling this"
              + " repeatedly.")
  @SneakyThrows
  public SheetRangeValues readSheetRange(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The range in A1 notation, as in \"<sheetId>!A1:G5\"")
          String range) {

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
          "Read several ranges of a spreadsheet in one call, which beats calling"
              + " FeishuSheetReadRange repeatedly. Ranges are A1 notation including the sheet id,"
              + " as in \"<sheetId>!A1:G5\", and FeishuListSheets returns the sheet id.")
  @SneakyThrows
  public List<SheetRangeValues> batchReadSheetRanges(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The ranges to read, each in A1 notation, as in \"<sheetId>!A1:G5\"")
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
          "Write a two-dimensional array into a range of a spreadsheet. Ranges are A1 notation"
              + " including the sheet id, as in \"<sheetId>!A1:G5\".\n"
              + "**Lock the sheet with FeishuLockSheet before writing and unlock it with"
              + " FeishuUnlockSheet afterwards**, so an edit does not collide with someone else's."
              + " If this task already called FeishuLockSheet for the same sheet and holds its"
              + " protectId, the sheet is locked already: write straight away rather than locking"
              + " again.\n"
              + "A cell can be a bare string or number, or a JSON object for a formula, a link, an"
              + " email, a mention or a dropdown; call FeishuSheetDataFormats for those shapes"
              + " before writing them. For several ranges at once, FeishuSheetBatchUpdateRanges is"
              + " cheaper.")
  @SneakyThrows
  public String updateSheetRange(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The range in A1 notation, as in \"<sheetId>!A1:G5\"") String range,
      @ToolParam(
              description =
                  "The rows to write; each cell a bare string or number, or a JSON object of one of"
                      + " the shapes FeishuSheetDataFormats describes")
          List<List<Object>> values) {

    if (values == null || values.isEmpty()) {
      return "There was nothing to write.";
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
    return "Wrote " + cells.size() + " rows to " + range + ".";
  }

  @Tool(
      name = "FeishuSheetBatchUpdateRanges",
      description =
          "Write to several ranges of a spreadsheet in one call, which beats calling"
              + " FeishuSheetUpdateRange repeatedly. Ranges are A1 notation including the sheet id,"
              + " as in \"<sheetId>!A1:G5\".\n"
              + "**Lock the sheet with FeishuLockSheet before writing and unlock it with"
              + " FeishuUnlockSheet afterwards**, so an edit does not collide with someone else's."
              + " If this task already called FeishuLockSheet for the same sheet and holds its"
              + " protectId, the sheet is locked already: write straight away rather than locking"
              + " again.\n"
              + "A cell can be a bare string or number, or a JSON object for a formula, a link, an"
              + " email, a mention or a dropdown; call FeishuSheetDataFormats for those shapes"
              + " before writing them.")
  @SneakyThrows
  public String batchUpdateSheetRanges(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The ranges to write and the rows for each")
          List<RangeValues> ranges) {

    if (ranges == null || ranges.isEmpty()) {
      return "There was nothing to write.";
    }

    final var valueRanges = new ArrayList<ValueRangeV2>();
    for (final var rangeValues : ranges) {
      final var parsedRange = parseRange(rangeValues.range());
      valueRanges.add(
          ValueRangeV2.builder().range(parsedRange).values(toCells(rangeValues.values())).build());
    }

    log.info("Writing {} range(s) to spreadsheet {}", valueRanges.size(), spreadsheetToken);
    feishuSheetsService.setValuesBatchV2(spreadsheetToken, valueRanges);
    return "Wrote " + valueRanges.size() + " ranges.";
  }

  @Tool(
      name = "FeishuSheetSetRangeStyle",
      description =
          "Style the cells of a range — font, colour, borders, alignment, number format — which is"
              + " a separate operation from writing their content. Ranges are A1 notation including"
              + " the sheet id, as in \"<sheetId>!A1:G5\".\n"
              + "**Before writing dates, set the range's formatter to a date format here (for"
              + " example \"yyyy/MM/dd\") and only then write the numbers standing for those dates"
              + " with FeishuSheetUpdateRange or FeishuSheetBatchUpdateRanges**, or the cells will"
              + " show plain numbers.\n"
              + "**Lock the sheet with FeishuLockSheet before writing and unlock it with"
              + " FeishuUnlockSheet afterwards**, so an edit does not collide with someone else's."
              + " If this task already called FeishuLockSheet for the same sheet and holds its"
              + " protectId, the sheet is locked already: write straight away rather than locking"
              + " again.\n"
              + "At most 5000 rows by 100 columns per call, and at most 30000 cells when setting"
              + " borders.")
  @SneakyThrows
  public String setRangeStyle(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The range in A1 notation, as in \"<sheetId>!A1:G5\"") String range,
      @ToolParam(description = "Bold", required = false) Boolean bold,
      @ToolParam(description = "Italic", required = false) Boolean italic,
      @ToolParam(
              description =
                  "Font size or line height: \"10pt\" for a size, between 9 and 36pt, or \"1.5\""
                      + " for a line height, which is fixed at 1.5px",
              required = false)
          String fontSize,
      @ToolParam(
              description =
                  "Decoration: 0 none, 1 underline, 2 strikethrough, 3 underline and strikethrough",
              required = false)
          Integer textDecoration,
      @ToolParam(
              description =
                  "Number or date format, as in \"yyyy/MM/dd\" for a date or \"0.00%\" for a"
                      + " percentage. Dates have to be formatted here before they are written",
              required = false)
          String formatter,
      @ToolParam(description = "Horizontal alignment: 0 left, 1 centre, 2 right", required = false)
          Integer hAlign,
      @ToolParam(description = "Vertical alignment: 0 top, 1 middle, 2 bottom", required = false)
          Integer vAlign,
      @ToolParam(description = "Font colour as a hex code, as in \"#000000\"", required = false)
          String foreColor,
      @ToolParam(
              description = "Background colour as a hex code, as in \"#21D11F\"",
              required = false)
          String backColor,
      @ToolParam(
              description =
                  "Border type: FULL_BORDER, OUTER_BORDER, INNER_BORDER, NO_BORDER, LEFT_BORDER,"
                      + " RIGHT_BORDER, TOP_BORDER or BOTTOM_BORDER",
              required = false)
          String borderType,
      @ToolParam(description = "Border colour as a hex code, as in \"#FF0000\"", required = false)
          String borderColor,
      @ToolParam(
              description = "Clear every style already on the range; false by default",
              required = false)
          Boolean clean) {

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
      return "There was no style to set.";
    }

    final var parsedRange = parseRange(range);
    log.info("Setting style {} on range {} of spreadsheet {}", style, range, spreadsheetToken);
    feishuSheetsService.setStyle(spreadsheetToken, parsedRange, style);
    return "Styled " + range + ".";
  }

  @Tool(
      name = "FeishuSheetDataFormats",
      description =
          "The cell types FeishuSheetUpdateRange and FeishuSheetBatchUpdateRanges can write, and"
              + " the JSON shape of each: strings, numbers, dates, links, emails, mentions of"
              + " people and of documents, formulas, dropdowns and inline styling. Call it before"
              + " writing anything richer than a bare string or number.")
  public String getSheetDataFormats() {
    return SHEET_DATA_FORMATS;
  }

  @Tool(
      name = "FeishuLockSheet",
      description =
          "Lock a sheet so nobody else edits it while you do. Call it before changing a sheet with"
              + " FeishuSheetUpdateRange or FeishuSheetBatchUpdateRanges, and call"
              + " FeishuUnlockSheet once you are done.\n"
              + "**Locking returns a protectId: remember it for the rest of this task. If this task"
              + " already locked the same sheet and holds its protectId, the sheet is locked"
              + " already — do not lock it again, which achieves nothing and only slows the work"
              + " down. Write with the lock you have and unlock once at the very end. If you are"
              + " unsure whether an earlier lock still holds, pass its protectId to"
              + " FeishuGetProtectedRanges and look, rather than locking afresh.**")
  public String lockSheet(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "Id of the sheet to lock") String sheetId,
      @ToolParam(description = "A note on the lock, such as why it was taken", required = false)
          String lockInfo) {
    final var protectId = feishuSheetsService.lockSheet(spreadsheetToken, sheetId, lockInfo);
    log.info(
        "Locked sheet {} of spreadsheet {}, protectId={}", sheetId, spreadsheetToken, protectId);
    return protectId == null || protectId.isBlank()
        ? "Locked sheet " + sheetId + "."
        : "Locked sheet "
            + sheetId
            + ", protectId="
            + protectId
            + ". Remember that protectId: this sheet does not need locking again.";
  }

  @Tool(
      name = "FeishuUnlockSheet",
      description =
          "Unlock a sheet that FeishuLockSheet locked, which usually means once the writing with"
              + " FeishuSheetUpdateRange or FeishuSheetBatchUpdateRanges is finished. One unlock"
              + " per sheet per task is enough: wait until every write is done rather than"
              + " unlocking after each one.")
  public String unlockSheet(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "Id of the sheet to unlock") String sheetId) {
    feishuSheetsService.unlockSheet(spreadsheetToken, sheetId);
    log.info("Unlocked sheet {} of spreadsheet {}", sheetId, spreadsheetToken);
    return "Unlocked sheet " + sheetId + ".";
  }

  @Tool(
      name = "FeishuGetProtectedRanges",
      description =
          "Look up protected ranges by protectId: which rows and columns are locked, the note"
              + " (lockInfo) left with the lock, and who may still edit them. At most five"
              + " protectIds per call, each one a value FeishuLockSheet returned earlier. Use it"
              + " before writing to the same sheet again, to see whether the lock you hold is still"
              + " good, rather than locking again and dealing with the conflict.")
  public List<ProtectedRange> getProtectedRanges(
      @ToolParam(description = "Spreadsheet token") String spreadsheetToken,
      @ToolParam(description = "The protectIds to look up, at most five") List<String> protectIds,
      @ToolParam(
              description =
                  "What kind of user id to return: userId (the default), openId or unionId. openId"
                      + " is the one to prefer",
              required = false)
          String memberType) {
    if (protectIds == null || protectIds.isEmpty()) {
      return List.of();
    }
    if (protectIds.size() > 5) {
      throw new IllegalArgumentException(
          "At most five protectIds per call, but " + protectIds.size() + " were given");
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
