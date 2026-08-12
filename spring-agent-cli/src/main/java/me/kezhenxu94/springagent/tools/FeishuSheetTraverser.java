package me.kezhenxu94.springagent.tools;

import com.lark.oapi.Client;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.service.sheets.v3.model.GetSpreadsheetSheetReq;
import com.lark.oapi.service.sheets.v3.model.Sheet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.bot.feishu.sheet.FeishuSheetsService;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRange;
import me.kezhenxu94.springagent.bot.feishu.sheet.ValueRangeV2;
import me.kezhenxu94.springagent.service.SpreadsheetResp;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuSheetTraverser {
  private final Client feishu;
  private final JsonMapper objectMapper;
  private final FeishuSheetsService feishuSheetsService;

  /**
   * Traverses a Feishu sheet and processes each row using the provided handler function.
   *
   * @param sheetToken The sheet token
   * @param sheetId The sheet ID
   * @param rowHandler Function to handle each row. Returns a ColumnRange indicating which columns
   *     were updated, or ColumnRange.none() if no updates were made.
   * @return A result message
   */
  @SneakyThrows
  public void traverseSheet(
      String sheetToken,
      String sheetId,
      BiFunction<List<JsonNode>, Integer, ColumnRange> rowHandler) {

    final var sheetResponse =
        feishu
            .sheets()
            .v3()
            .spreadsheetSheet()
            .get(
                GetSpreadsheetSheetReq.newBuilder()
                    .spreadsheetToken(sheetToken)
                    .sheetId(sheetId)
                    .build());

    if (sheetResponse.getCode() != 0) {
      log.error("Failed to get feishu sheet: {}", sheetResponse.getMsg());
      throw new RuntimeException("Failed to get feishu sheet: " + sheetResponse.getMsg());
    }

    final var sheet = sheetResponse.getData().getSheet();

    final var sheetResp =
        feishu.get(
            String.format(
                "/open-apis/sheets/v2/spreadsheets/%s/values/%s",
                sheetToken,
                ValueRange.Range.builder()
                    .sheetId(sheet.getSheetId())
                    .rowStart(1)
                    .columnStart(1)
                    .rowEnd(sheet.getGridProperties().getRowCount())
                    .columnEnd(sheet.getGridProperties().getColumnCount() + 1)
                    .build()),
            null,
            AccessTokenType.Tenant);

    final var readSpreadsheetResp =
        objectMapper.readValue(sheetResp.getBody(), SpreadsheetResp.class);
    if (!readSpreadsheetResp.success()) {
      log.error(
          "read spreadsheet failed, code: {}, msg: {}, logId: {}",
          readSpreadsheetResp.getCode(),
          readSpreadsheetResp.getMsg(),
          readSpreadsheetResp.getRequestId());
      throw new RuntimeException("Failed to get feishu sheet: " + readSpreadsheetResp.getMsg());
    }

    log.debug("Fetched sheet: {}", readSpreadsheetResp.getData().getValueRange());

    log.debug(
        "Fetched range values for sheet: {}, rows: {}, cols: {}",
        sheet.getSheetId(),
        sheet.getGridProperties().getRowCount(),
        sheet.getGridProperties().getColumnCount());

    final var values = readSpreadsheetResp.getData().getValueRange().getValues();

    var maxRows = sheet.getGridProperties().getRowCount();
    var maxCols = sheet.getGridProperties().getColumnCount() - 1;

    while (maxRows > 0
        && (values.get(maxRows - 1).get(0) == null
            || values.get(maxRows - 1).get(0).asString().isBlank())) {
      log.debug("Skipping empty row: {}, {}", maxRows, values.get(maxRows - 1).get(0));
      maxRows--;
    }

    while (maxCols > 0
        && (values.get(0).get(maxCols) == null
            || values.get(0).get(maxCols).asString().isBlank())) {
      maxCols--;
    }
    maxCols++;

    log.debug(
        "Processing sheet: {}, maxRows: {}, maxCols: {}", sheet.getSheetId(), maxRows, maxCols);

    for (var row = 0; row < maxRows; row++) {
      try {
        final var rowValues = values.get(row);

        if (rowValues.get(0) == null || rowValues.get(0).isNull()) {
          continue;
        }

        final var columnRange = rowHandler.apply(rowValues, row);

        if (columnRange.isUpdated()) {
          flush(sheetToken, sheet, values, row + 1, columnRange);
        }

      } catch (Exception e) {
        log.error("Error processing sheet: {}, {}", sheet.getSheetId(), e.getMessage(), e);
        throw new RuntimeException("Error processing sheet: " + e.getMessage());
      }
    }
  }

  /**
   * Flushes changes to the sheet for a specific row and column range.
   *
   * @param sheetToken The sheet token
   * @param sheet The sheet object
   * @param values All sheet values
   * @param row The row number (1-based)
   * @param columnRange The range of columns that were updated
   */
  @SneakyThrows
  private void flush(
      String sheetToken,
      Sheet sheet,
      List<List<JsonNode>> values,
      int row,
      ColumnRange columnRange) {
    log.debug(
        "Flushing changes to sheet: {}, row: {}, columnRange: {} to {}",
        sheet.getSheetId(),
        row,
        columnRange.getStartColumn(),
        columnRange.getEndColumn());

    final var updateRange =
        ValueRangeV2.builder()
            .range(
                ValueRange.Range.builder()
                    .sheetId(sheet.getSheetId())
                    .rowStart(row)
                    .columnStart(columnRange.getStartColumn())
                    .rowEnd(row)
                    .columnEnd(columnRange.getEndColumn() + 1)
                    .build())
            .values(
                values.subList(row - 1, row).stream()
                    .map(
                        r ->
                            r.subList(columnRange.getStartColumn() - 1, columnRange.getEndColumn()))
                    .toList())
            .build();

    for (int i = 0; i < 10; i++) {
      try {
        feishuSheetsService.setValuesV2(sheetToken, updateRange);
        break;
      } catch (Exception e) {
        log.error("Failed to update sheet, retry later: {}", columnRange, e);
        TimeUnit.SECONDS.sleep(10);
      }
    }
  }

  /** Represents a range of columns that were updated in a sheet row. */
  @Data
  @Builder
  public static class ColumnRange {
    int startColumn;
    int endColumn;

    public static ColumnRange of(int startColumn, int endColumn) {
      return new ColumnRange(startColumn, endColumn);
    }

    public static ColumnRange singleColumn(int column) {
      return new ColumnRange(column, column);
    }

    public static ColumnRange none() {
      return new ColumnRange(-1, -1);
    }

    public boolean isUpdated() {
      return startColumn > 0 && endColumn > 0;
    }
  }
}
