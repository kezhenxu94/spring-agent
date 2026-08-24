package me.kezhenxu94.springagent.integration.feishu.bitable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.bitable.v1.model.App;
import com.lark.oapi.service.bitable.v1.model.AppTableCreateHeader;
import com.lark.oapi.service.bitable.v1.model.AppTableRecord;
import com.lark.oapi.service.bitable.v1.model.AppTableView;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableReq;
import com.lark.oapi.service.bitable.v1.model.BatchCreateAppTableReqBody;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableReq;
import com.lark.oapi.service.bitable.v1.model.BatchDeleteAppTableReqBody;
import com.lark.oapi.service.bitable.v1.model.BatchGetAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchGetAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.BatchUpdateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.BatchUpdateAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.CreateAppReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableReq;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableReqBody;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRespBody;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.DeleteAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.DisplayApp;
import com.lark.oapi.service.bitable.v1.model.DisplayAppV2;
import com.lark.oapi.service.bitable.v1.model.FilterInfo;
import com.lark.oapi.service.bitable.v1.model.GetAppReq;
import com.lark.oapi.service.bitable.v1.model.GetAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableFieldReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableReq;
import com.lark.oapi.service.bitable.v1.model.ListAppTableViewReq;
import com.lark.oapi.service.bitable.v1.model.PatchAppTableReq;
import com.lark.oapi.service.bitable.v1.model.PatchAppTableReqBody;
import com.lark.oapi.service.bitable.v1.model.ReqApp;
import com.lark.oapi.service.bitable.v1.model.ReqTable;
import com.lark.oapi.service.bitable.v1.model.ReqView;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.Sort;
import com.lark.oapi.service.bitable.v1.model.UpdateAppReq;
import com.lark.oapi.service.bitable.v1.model.UpdateAppReqBody;
import com.lark.oapi.service.bitable.v1.model.UpdateAppTableRecordReq;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Every call this module makes against the Feishu bitable (多维表格) v1 API.
 *
 * <p>All of it goes through the SDK's typed resources, which model bitable v1 completely, so unlike
 * the spreadsheet service there is no raw request path here and no Jackson DTO of our own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBitableService {

  private final Client feishu;

  @SneakyThrows
  public App createApp(final String folderToken, final String name, final String timeZone) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .app()
            .create(
                CreateAppReq.newBuilder()
                    .reqApp(
                        ReqApp.newBuilder()
                            .name(name)
                            .folderToken(folderToken)
                            .timeZone(timeZone)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error("Failed to create bitable '{}': {}, {}", name, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to create bitable: " + resp.getMsg());
    }
    final var app = resp.getData().getApp();
    log.info("Created bitable '{}': appToken={}", name, app.getAppToken());
    return app;
  }

  @SneakyThrows
  public DisplayApp getApp(final String appToken) {
    final var resp =
        feishu.bitable().v1().app().get(GetAppReq.newBuilder().appToken(appToken).build());
    if (!resp.success()) {
      log.error("Failed to get bitable {}: {}, {}", appToken, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to get bitable metadata: " + resp.getMsg());
    }
    return resp.getData().getApp();
  }

  @SneakyThrows
  public DisplayAppV2 updateApp(
      final String appToken, final String name, final Boolean isAdvanced) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .app()
            .update(
                UpdateAppReq.newBuilder()
                    .appToken(appToken)
                    .updateAppReqBody(
                        UpdateAppReqBody.newBuilder().name(name).isAdvanced(isAdvanced).build())
                    .build());
    if (!resp.success()) {
      log.error("Failed to update bitable {}: {}, {}", appToken, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to update bitable metadata: " + resp.getMsg());
    }
    log.info("Updated bitable {}: name={}, isAdvanced={}", appToken, name, isAdvanced);
    return resp.getData().getApp();
  }

  @SneakyThrows
  public String listTables(final String appToken, final String pageToken, final Integer pageSize) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTable()
            .list(
                ListAppTableReq.newBuilder()
                    .appToken(appToken)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to list tables of bitable {}: {}, {}", appToken, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to list bitable tables: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public CreateAppTableRespBody createTable(
      final String appToken,
      final String name,
      final String defaultViewName,
      final String fieldsJson) {
    final var fields =
        fieldsJson == null || fieldsJson.isBlank()
            ? null
            : parse(fieldsJson, AppTableCreateHeader[].class, "fieldsJson");
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTable()
            .create(
                CreateAppTableReq.newBuilder()
                    .appToken(appToken)
                    .createAppTableReqBody(
                        CreateAppTableReqBody.newBuilder()
                            .table(
                                ReqTable.newBuilder()
                                    .name(name)
                                    .defaultViewName(defaultViewName)
                                    .fields(fields)
                                    .build())
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create table '{}' in bitable {}: {}, {}",
          name,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create bitable table: " + resp.getMsg());
    }
    log.info(
        "Created table '{}' in bitable {}: tableId={}",
        name,
        appToken,
        resp.getData().getTableId());
    return resp.getData();
  }

  @SneakyThrows
  public List<String> batchCreateTables(final String appToken, final List<String> names) {
    final var tables =
        names.stream()
            .map(name -> ReqTable.newBuilder().name(name).build())
            .toArray(ReqTable[]::new);
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTable()
            .batchCreate(
                BatchCreateAppTableReq.newBuilder()
                    .appToken(appToken)
                    .batchCreateAppTableReqBody(
                        BatchCreateAppTableReqBody.newBuilder().tables(tables).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create {} tables in bitable {}: {}, {}",
          names.size(),
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create bitable tables: " + resp.getMsg());
    }
    final var tableIds = List.of(resp.getData().getTableIds());
    log.info("Created tables {} in bitable {}", tableIds, appToken);
    return tableIds;
  }

  @SneakyThrows
  public String renameTable(final String appToken, final String tableId, final String name) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTable()
            .patch(
                PatchAppTableReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .patchAppTableReqBody(PatchAppTableReqBody.newBuilder().name(name).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to rename table {} of bitable {}: {}, {}",
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to rename bitable table: " + resp.getMsg());
    }
    log.info("Renamed table {} of bitable {} to '{}'", tableId, appToken, name);
    return resp.getData().getName();
  }

  @SneakyThrows
  public void batchDeleteTables(final String appToken, final List<String> tableIds) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTable()
            .batchDelete(
                BatchDeleteAppTableReq.newBuilder()
                    .appToken(appToken)
                    .batchDeleteAppTableReqBody(
                        BatchDeleteAppTableReqBody.newBuilder()
                            .tableIds(tableIds.toArray(String[]::new))
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to delete tables {} of bitable {}: {}, {}",
          tableIds,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to delete bitable tables: " + resp.getMsg());
    }
    log.info("Deleted tables {} of bitable {}", tableIds, appToken);
  }

  @SneakyThrows
  public String listFields(
      final String appToken,
      final String tableId,
      final String viewId,
      final String pageToken,
      final Integer pageSize) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableField()
            .list(
                ListAppTableFieldReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .viewId(viewId)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to list fields of table {} in bitable {}: {}, {}",
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to list bitable fields: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String listViews(
      final String appToken, final String tableId, final String pageToken, final Integer pageSize) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableView()
            .list(
                ListAppTableViewReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to list views of table {} in bitable {}: {}, {}",
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to list bitable views: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String getView(final String appToken, final String tableId, final String viewId) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableView()
            .get(
                GetAppTableViewReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .viewId(viewId)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to get view {} of table {} in bitable {}: {}, {}",
          viewId,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get bitable view: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public AppTableView createView(
      final String appToken, final String tableId, final String viewName, final String viewType) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableView()
            .create(
                CreateAppTableViewReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .reqView(ReqView.newBuilder().viewName(viewName).viewType(viewType).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create view '{}' in table {} of bitable {}: {}, {}",
          viewName,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create bitable view: " + resp.getMsg());
    }
    final var view = resp.getData().getView();
    log.info(
        "Created view '{}' in table {} of bitable {}: viewId={}",
        viewName,
        tableId,
        appToken,
        view.getViewId());
    return view;
  }

  @SneakyThrows
  public void deleteView(final String appToken, final String tableId, final String viewId) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableView()
            .delete(
                DeleteAppTableViewReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .viewId(viewId)
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to delete view {} of table {} in bitable {}: {}, {}",
          viewId,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to delete bitable view: " + resp.getMsg());
    }
    log.info("Deleted view {} of table {} in bitable {}", viewId, tableId, appToken);
  }

  @SneakyThrows
  public String searchRecords(
      final String appToken,
      final String tableId,
      final String viewId,
      final List<String> fieldNames,
      final String filterJson,
      final String sortJson,
      final Boolean automaticFields,
      final String pageToken,
      final Integer pageSize) {
    final var filter =
        filterJson == null || filterJson.isBlank()
            ? null
            : parse(filterJson, FilterInfo.class, "filterJson");
    final var sort =
        sortJson == null || sortJson.isBlank() ? null : parse(sortJson, Sort[].class, "sortJson");
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .search(
                SearchAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .pageToken(pageToken)
                    .pageSize(pageSize)
                    .searchAppTableRecordReqBody(
                        SearchAppTableRecordReqBody.newBuilder()
                            .viewId(viewId)
                            .fieldNames(
                                fieldNames == null || fieldNames.isEmpty()
                                    ? null
                                    : fieldNames.toArray(String[]::new))
                            .filter(filter)
                            .sort(sort)
                            .automaticFields(automaticFields)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to search records of table {} in bitable {}: {}, {}",
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to search bitable records: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String batchGetRecords(
      final String appToken,
      final String tableId,
      final List<String> recordIds,
      final Boolean withSharedUrl,
      final Boolean automaticFields) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .batchGet(
                BatchGetAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .batchGetAppTableRecordReqBody(
                        BatchGetAppTableRecordReqBody.newBuilder()
                            .recordIds(recordIds.toArray(String[]::new))
                            .withSharedUrl(withSharedUrl)
                            .automaticFields(automaticFields)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to get records {} of table {} in bitable {}: {}, {}",
          recordIds,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to get bitable records: " + resp.getMsg());
    }
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String createRecord(
      final String appToken,
      final String tableId,
      final String fieldsJson,
      final String clientToken,
      final Boolean ignoreConsistencyCheck) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .create(
                CreateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .clientToken(clientToken)
                    .ignoreConsistencyCheck(ignoreConsistencyCheck)
                    .appTableRecord(
                        AppTableRecord.newBuilder()
                            .fields(parseFields(fieldsJson, "fieldsJson"))
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create a record in table {} of bitable {}: {}, {}",
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create bitable record: " + resp.getMsg());
    }
    final var record = resp.getData().getRecord();
    log.info(
        "Created record {} in table {} of bitable {}", record.getRecordId(), tableId, appToken);
    return Jsons.DEFAULT.toJson(record);
  }

  @SneakyThrows
  public String batchCreateRecords(
      final String appToken,
      final String tableId,
      final String recordsJson,
      final String clientToken,
      final Boolean ignoreConsistencyCheck) {
    final var records = parseRecords(recordsJson);
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .batchCreate(
                BatchCreateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .clientToken(clientToken)
                    .ignoreConsistencyCheck(ignoreConsistencyCheck)
                    .batchCreateAppTableRecordReqBody(
                        BatchCreateAppTableRecordReqBody.newBuilder().records(records).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create {} records in table {} of bitable {}: {}, {}",
          records.length,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create bitable records: " + resp.getMsg());
    }
    log.info("Created {} record(s) in table {} of bitable {}", records.length, tableId, appToken);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public String updateRecord(
      final String appToken,
      final String tableId,
      final String recordId,
      final String fieldsJson,
      final Boolean ignoreConsistencyCheck) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .update(
                UpdateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .recordId(recordId)
                    .ignoreConsistencyCheck(ignoreConsistencyCheck)
                    .appTableRecord(
                        AppTableRecord.newBuilder()
                            .fields(parseFields(fieldsJson, "fieldsJson"))
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to update record {} in table {} of bitable {}: {}, {}",
          recordId,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to update bitable record: " + resp.getMsg());
    }
    log.info("Updated record {} in table {} of bitable {}", recordId, tableId, appToken);
    return Jsons.DEFAULT.toJson(resp.getData().getRecord());
  }

  @SneakyThrows
  public String batchUpdateRecords(
      final String appToken,
      final String tableId,
      final String recordsJson,
      final Boolean ignoreConsistencyCheck) {
    final var records = parseRecords(recordsJson);
    for (final var record : records) {
      if (record.getRecordId() == null || record.getRecordId().isBlank()) {
        throw new IllegalArgumentException(
            "every element of recordsJson must carry a record_id to update");
      }
    }
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .batchUpdate(
                BatchUpdateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .ignoreConsistencyCheck(ignoreConsistencyCheck)
                    .batchUpdateAppTableRecordReqBody(
                        BatchUpdateAppTableRecordReqBody.newBuilder().records(records).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to update {} records in table {} of bitable {}: {}, {}",
          records.length,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to update bitable records: " + resp.getMsg());
    }
    log.info("Updated {} record(s) in table {} of bitable {}", records.length, tableId, appToken);
    return Jsons.DEFAULT.toJson(resp.getData());
  }

  @SneakyThrows
  public void batchDeleteRecords(
      final String appToken, final String tableId, final List<String> recordIds) {
    final var resp =
        feishu
            .bitable()
            .v1()
            .appTableRecord()
            .batchDelete(
                BatchDeleteAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .batchDeleteAppTableRecordReqBody(
                        BatchDeleteAppTableRecordReqBody.newBuilder()
                            .records(recordIds.toArray(String[]::new))
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to delete records {} of table {} in bitable {}: {}, {}",
          recordIds,
          tableId,
          appToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to delete bitable records: " + resp.getMsg());
    }
    log.info("Deleted {} record(s) of table {} in bitable {}", recordIds.size(), tableId, appToken);
  }

  /**
   * Reads a record's {@code fields} object, leaving each value as a Gson {@link
   * com.google.gson.JsonElement} rather than widening it to {@code Object}.
   *
   * <p>The distinction is load-bearing: Gson's adapter for {@code Object} reads every JSON number
   * as a {@code Double}, so a date field's millisecond timestamp goes out again as {@code 1.7E12}
   * and an integer as {@code 3.0}. A {@code JsonElement} keeps the number exactly as it was
   * written, and every other field shape — the nested objects a url or person field takes, the
   * arrays a link or attachment field takes — round-trips verbatim as well.
   */
  private static Map<String, Object> parseFields(final String fieldsJson, final String argument) {
    return toFields(parse(fieldsJson, JsonObject.class, argument));
  }

  /**
   * Reads a record's {@code fields} object, leaving each value as a Gson {@link
   * com.google.gson.JsonElement} rather than widening it to {@code Object}.
   *
   * <p>The distinction is load-bearing: Gson's adapter for {@code Object} reads every JSON number
   * as a {@code Double}, so a date field's millisecond timestamp goes out again as {@code 1.7E12}
   * and an integer as {@code 3.0}. A {@code JsonElement} keeps the number exactly as it was
   * written, and every other field shape — the nested objects a url or person field takes, the
   * arrays a link or attachment field takes — round-trips verbatim as well.
   */
  private static Map<String, Object> toFields(final JsonObject fields) {
    final Map<String, Object> map = new LinkedHashMap<>();
    fields.entrySet().forEach(entry -> map.put(entry.getKey(), entry.getValue()));
    return map;
  }

  /**
   * The most records one batch create or update takes. Checked here rather than in the tool layer
   * because a tool is handed the records as JSON and cannot count them without parsing; Feishu's
   * own error names neither the limit nor the argument that broke it.
   */
  private static final int MAX_RECORDS_PER_BATCH = 1000;

  /**
   * Reads an array of {@code {"record_id": ..., "fields": {...}}} objects. Built by hand rather
   * than with {@code fromJson(json, AppTableRecord[].class)} so that the field values go through
   * {@link #toFields}; binding the record type directly would widen them to {@code Object}.
   */
  private static AppTableRecord[] parseRecords(final String recordsJson) {
    final var parsed = parse(recordsJson, JsonArray.class, "recordsJson");
    if (parsed.size() > MAX_RECORDS_PER_BATCH) {
      throw new IllegalArgumentException(
          "At most "
              + MAX_RECORDS_PER_BATCH
              + " records in one call, recordsJson holds "
              + parsed.size());
    }
    final var records = new AppTableRecord[parsed.size()];
    for (int i = 0; i < parsed.size(); i++) {
      final var element = parsed.get(i);
      if (!element.isJsonObject()) {
        throw new IllegalArgumentException(
            "recordsJson element " + i + " must be a JSON object with a fields member");
      }
      final var object = element.getAsJsonObject();
      final var recordId = object.get("record_id");
      final var fields = object.get("fields");
      if (fields == null || !fields.isJsonObject()) {
        throw new IllegalArgumentException(
            "recordsJson element " + i + " must carry a fields object");
      }
      records[i] =
          AppTableRecord.newBuilder()
              .recordId(recordId == null || recordId.isJsonNull() ? null : recordId.getAsString())
              .fields(toFields(fields.getAsJsonObject()))
              .build();
    }
    return records;
  }

  /**
   * Reads one of the JSON-string arguments a tool passes down, failing with the argument's own
   * name.
   *
   * <p>Gson raises a {@link JsonSyntaxException} whose message names the offending path but not
   * where the JSON came from, and several of these methods take more than one JSON argument, so on
   * its own it does not tell the caller which one to fix.
   */
  private static <T> T parse(final String json, final Class<T> type, final String argument) {
    final T parsed;
    try {
      parsed = Jsons.DEFAULT.fromJson(json, type);
    } catch (final JsonSyntaxException e) {
      throw new IllegalArgumentException(
          argument + " is not JSON of the shape expected: " + e.getMessage(), e);
    }
    if (parsed == null) {
      throw new IllegalArgumentException(argument + " must be valid non-null JSON");
    }
    return parsed;
  }
}
