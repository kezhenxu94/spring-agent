package me.kezhenxu94.springagent.bot.feishu.sheet;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.bot.feishu.model.FeishuResponse;
import me.kezhenxu94.springagent.bot.feishu.model.Message;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.GetProtectedRangesDTO;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.GetValueRangeBatchDTOV2;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.GetValueRangeDTO;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.GetValueRangeDTOV2;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.ProtectedRange;
import me.kezhenxu94.springagent.bot.feishu.model.spreadsheet.Sheet;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
@RegisterReflectionForBinding({Message.class})
public class FeishuSheetsService {

  private static final String SHEETS_BASE = "/open-apis/sheets";

  final JsonMapper objectMapper;
  private final Client feishu;

  @SneakyThrows
  public List<Sheet> getSheets(final String spreadsheetToken) {
    final var raw =
        feishu.get(
            SHEETS_BASE + "/v3/spreadsheets/" + spreadsheetToken + "/sheets/query",
            null,
            AccessTokenType.Tenant);
    final FeishuResponse<Sheets> response = readResponse(raw, Sheets.class);
    if (response == null || response.code() != 0) {
      log.error("Failed to get sheet: {}", response);
      throw new IllegalStateException("Failed to get sheet");
    }
    return response.data().sheets();
  }

  public void setValues(final String spreadsheetToken, final ValueRange valueRange) {
    doSetValues(spreadsheetToken, valueRange);
  }

  public void setValuesV2(final String spreadsheetToken, final ValueRangeV2 valueRange) {
    doSetValues(spreadsheetToken, valueRange);
  }

  public SheetSummary addSheet(
      final String spreadsheetToken, final String title, final Integer index) {
    final var properties =
        index == null ? Map.of("title", title) : Map.of("title", title, "index", index);
    final var reply =
        sheetsBatchUpdateOne(spreadsheetToken, "addSheet", Map.of("properties", properties));
    log.info("Added sheet '{}' to spreadsheet {}", title, spreadsheetToken);
    return toSheetSummary(spreadsheetToken, "addSheet", reply);
  }

  public SheetSummary copySheet(
      final String spreadsheetToken, final String sourceSheetId, final String newTitle) {
    final var destination = newTitle == null ? Map.of() : Map.of("title", newTitle);
    final var reply =
        sheetsBatchUpdateOne(
            spreadsheetToken,
            "copySheet",
            Map.of("source", Map.of("sheetId", sourceSheetId), "destination", destination));
    log.info("Copied sheet {} of spreadsheet {} to a new sheet", sourceSheetId, spreadsheetToken);
    return toSheetSummary(spreadsheetToken, "copySheet", reply);
  }

  private SheetSummary toSheetSummary(
      final String spreadsheetToken, final String opName, final JsonNode reply) {
    final var properties = reply.path("properties");
    if (!properties.isObject()) {
      log.error(
          "{} on spreadsheet {} did not return a properties object: {}",
          opName,
          spreadsheetToken,
          reply);
      throw new IllegalStateException(
          "Failed to " + opName + " on spreadsheet " + spreadsheetToken + ": missing properties");
    }
    return objectMapper.convertValue(properties, SheetSummary.class);
  }

  public void deleteSheet(final String spreadsheetToken, final String sheetId) {
    final var reply =
        sheetsBatchUpdateOne(spreadsheetToken, "deleteSheet", Map.of("sheetId", sheetId));
    if (!reply.path("result").asBoolean(false)) {
      log.error(
          "Failed to delete sheet {} of spreadsheet {}: {}", sheetId, spreadsheetToken, reply);
      throw new IllegalStateException(
          "Failed to delete sheet " + sheetId + " of spreadsheet " + spreadsheetToken);
    }
    log.info("Deleted sheet {} of spreadsheet {}", sheetId, spreadsheetToken);
  }

  @SneakyThrows
  private JsonNode sheetsBatchUpdateOne(
      final String spreadsheetToken, final String opName, final Map<String, Object> opBody) {
    final var body = toGsonSafe(Map.of("requests", List.of(Map.of(opName, opBody))));
    final var raw =
        feishu.post(
            SHEETS_BASE + "/v2/spreadsheets/" + spreadsheetToken + "/sheets_batch_update",
            body,
            AccessTokenType.Tenant);
    final FeishuResponse<JsonNode> response = readResponse(raw, JsonNode.class);
    if (response == null || response.code() != 0 || response.data() == null) {
      log.error("Failed to {} on spreadsheet {}: {}", opName, spreadsheetToken, response);
      throw new IllegalStateException(
          "Failed to " + opName + " on spreadsheet " + spreadsheetToken);
    }
    final var reply = response.data().path("replies").path(0).path(opName);
    if (reply.isMissingNode()) {
      log.error(
          "{} on spreadsheet {} returned no matching reply: {}",
          opName,
          spreadsheetToken,
          response);
      throw new IllegalStateException(
          "Failed to " + opName + " on spreadsheet " + spreadsheetToken + ": no reply returned");
    }
    return reply;
  }

  @SneakyThrows
  public void setValuesBatchV2(
      final String spreadsheetToken, final List<ValueRangeV2> valueRanges) {
    final var body = toGsonSafe(Map.of("valueRanges", valueRanges));
    final var raw =
        feishu.post(
            SHEETS_BASE + "/v2/spreadsheets/" + spreadsheetToken + "/values_batch_update",
            body,
            AccessTokenType.Tenant);
    final FeishuResponse<Object> response = readResponse(raw, Object.class);
    if (response == null || response.code() != 0) {
      log.error("Failed to batch set sheet values: {}", response);
      throw new IllegalStateException("Failed to batch set sheet values");
    }
    log.info("Batch set sheet values successfully: {}", response);
  }

  @SneakyThrows
  void doSetValues(final String spreadsheetToken, final Object valueRange) {
    final var body = toGsonSafe(Map.of("valueRange", valueRange));
    final var raw =
        feishu.put(
            SHEETS_BASE + "/v2/spreadsheets/" + spreadsheetToken + "/values",
            body,
            AccessTokenType.Tenant);
    final FeishuResponse<Object> response = readResponse(raw, Object.class);
    if (response == null || response.code() != 0) {
      log.error("Failed to set sheet values: {}", response);
      throw new IllegalStateException("Failed to set sheet values");
    }
    log.info("Set sheet values successfully: {}", response);
  }

  /**
   * @return the protectId of the resulting protected range, or {@code null} if none was reported.
   */
  public String lockSheet(
      final String spreadsheetToken, final String sheetId, final String lockInfo) {
    return updateSheetLock(spreadsheetToken, sheetId, "LOCK", lockInfo);
  }

  public void unlockSheet(final String spreadsheetToken, final String sheetId) {
    updateSheetLock(spreadsheetToken, sheetId, "UNLOCK", null);
  }

  @SneakyThrows
  String updateSheetLock(
      final String spreadsheetToken,
      final String sheetId,
      final String lock,
      final String lockInfo) {
    final var protect =
        lockInfo == null ? Map.of("lock", lock) : Map.of("lock", lock, "lockInfo", lockInfo);
    final var body =
        toGsonSafe(
            Map.of(
                "requests",
                List.of(
                    Map.of(
                        "updateSheet",
                        Map.of("properties", Map.of("sheetId", sheetId, "protect", protect))))));

    final var raw =
        feishu.post(
            SHEETS_BASE + "/v2/spreadsheets/" + spreadsheetToken + "/sheets_batch_update",
            body,
            AccessTokenType.Tenant);
    final FeishuResponse<JsonNode> response = readResponse(raw, JsonNode.class);
    if (response == null || response.code() != 0) {
      log.error(
          "Failed to {} sheet {} of spreadsheet {}: {}", lock, sheetId, spreadsheetToken, response);
      throw new IllegalStateException(
          "Failed to " + lock + " sheet " + sheetId + " of spreadsheet " + spreadsheetToken);
    }
    log.info("{} sheet {} of spreadsheet {} successfully", lock, sheetId, spreadsheetToken);
    final var data = response.data();
    if (data == null) {
      return null;
    }
    final var protectId =
        data.path("replies").path(0).path("updateSheet").path("properties").path("protectId");
    return protectId.isMissingNode() || protectId.isNull() ? null : protectId.asString(null);
  }

  @SneakyThrows
  public void setStyle(
      final String spreadsheetToken,
      final ValueRange.Range range,
      final Map<String, Object> style) {
    final var body =
        toGsonSafe(Map.of("appendStyle", Map.of("range", range.toString(), "style", style)));
    final var raw =
        feishu.put(
            SHEETS_BASE + "/v2/spreadsheets/" + spreadsheetToken + "/style",
            body,
            AccessTokenType.Tenant);
    final FeishuResponse<Object> response = readResponse(raw, Object.class);
    if (response == null || response.code() != 0) {
      log.error("Failed to set sheet style: {}", response);
      throw new IllegalStateException("Failed to set sheet style");
    }
    log.info("Set sheet style successfully: {}", response);
  }

  @SneakyThrows
  public List<ProtectedRange> getProtectedRanges(
      final String spreadsheetToken, final List<String> protectIds, final String memberType) {
    if (protectIds == null || protectIds.isEmpty()) {
      return List.of();
    }

    final var joinedIds = String.join(",", protectIds);
    final var resolvedMemberType =
        memberType == null || memberType.isBlank() ? "userId" : memberType;
    final var raw =
        feishu.get(
            SHEETS_BASE
                + "/v2/spreadsheets/"
                + spreadsheetToken
                + "/protected_range_batch_get?protectIds="
                + joinedIds
                + "&memberType="
                + resolvedMemberType,
            null,
            AccessTokenType.Tenant);
    final FeishuResponse<GetProtectedRangesDTO> response =
        readResponse(raw, GetProtectedRangesDTO.class);
    if (response == null || response.code() != 0 || response.data() == null) {
      log.error("Failed to get protected ranges: {}", response);
      throw new IllegalStateException("Failed to get protected ranges");
    }
    final var protectedRanges = response.data().protectedRanges();
    return protectedRanges == null ? List.of() : protectedRanges;
  }

  public GetValueRangeDTO getRangeValues(
      final String spreadsheetToken, final ValueRange.Range range) {
    return doGetRangeValues(spreadsheetToken, range, GetValueRangeDTO.class);
  }

  public GetValueRangeDTOV2 getRangeValuesV2(
      final String spreadsheetToken, final ValueRange.Range range) {
    return doGetRangeValues(spreadsheetToken, range, GetValueRangeDTOV2.class);
  }

  @SneakyThrows
  public GetValueRangeBatchDTOV2 getRangeValuesBatchV2(
      final String spreadsheetToken, final List<ValueRange.Range> ranges) {
    if (ranges == null || ranges.isEmpty()) {
      return GetValueRangeBatchDTOV2.builder()
          .spreadsheetToken(spreadsheetToken)
          .valueRanges(List.of())
          .build();
    }

    final var joinedRanges = ranges.stream().map(Object::toString).collect(Collectors.joining(","));
    final var raw =
        feishu.get(
            SHEETS_BASE
                + "/v2/spreadsheets/"
                + spreadsheetToken
                + "/values_batch_get?ranges="
                + joinedRanges
                + "&valueRenderOption=FormattedValue",
            null,
            AccessTokenType.Tenant);
    final FeishuResponse<GetValueRangeBatchDTOV2> response =
        readResponse(raw, GetValueRangeBatchDTOV2.class);
    if (response == null || response.code() != 0 || response.data() == null) {
      log.error("Failed to batch get sheet range values: {}", response);
      throw new IllegalStateException("Failed to batch get sheet range values");
    }
    return response.data();
  }

  @SneakyThrows
  <T> T doGetRangeValues(
      final String spreadsheetToken, final ValueRange.Range range, final Class<?> clazz) {
    final var raw =
        feishu.get(
            SHEETS_BASE
                + "/v2/spreadsheets/"
                + spreadsheetToken
                + "/values/"
                + range
                + "?valueRenderOption=FormattedValue",
            null,
            AccessTokenType.Tenant);
    final FeishuResponse<T> response = readResponse(raw, clazz);
    if (response == null || response.code() != 0) {
      log.error("Failed to get sheet range values: {}", response);
      throw new IllegalStateException("Failed to get sheet range values");
    }
    return response.data();
  }

  /**
   * Converts a request payload built from our Jackson-annotated model types (e.g. {@link
   * ValueRange.Range}'s custom {@code "<sheetId>!A1:B2"} serialization) into a plain
   * Map/List/primitive tree. The Feishu SDK's raw {@code get/post/put} methods serialize the
   * request body with Gson, which knows nothing about our Jackson serializers/naming strategies, so
   * the payload must already be in its final JSON-equivalent shape before handing it off.
   */
  private Object toGsonSafe(final Object body) {
    return objectMapper.convertValue(body, Object.class);
  }

  @SneakyThrows
  private <T> FeishuResponse<T> readResponse(final RawResponse raw, final Class<?> dataType) {
    final var type =
        objectMapper.getTypeFactory().constructParametricType(FeishuResponse.class, dataType);
    return objectMapper.readValue(raw.getBody(), type);
  }

  @Builder
  @Jacksonized
  public record Sheets(List<Sheet> sheets) {}

  @Builder
  @Jacksonized
  public record SheetSummary(String sheetId, String title, int index) {}
}
