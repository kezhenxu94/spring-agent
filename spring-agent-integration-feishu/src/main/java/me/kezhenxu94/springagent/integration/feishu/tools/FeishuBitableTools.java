package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.bitable.v1.model.AppTableField;
import java.io.File;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.integration.feishu.bitable.FeishuBitableService;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bitable (多维表格) surface offered to a run: a base, its tables, their views and their records.
 *
 * <p>A base differs from a spreadsheet in what it is good for, and the descriptions here lean on
 * that: a spreadsheet is a grid of cells, a base is typed columns that can be filtered, sorted and
 * viewed several ways. Anything the user will want to query later belongs in a base; {@code
 * FeishuCreateSpreadsheet} is still the right answer for a one-off dump of rows.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuBitableTools {

  /**
   * The largest batch each endpoint accepts. Checked here rather than left to Feishu because the
   * error it returns names neither the limit nor which argument broke it, so a model cannot tell
   * from the failure that it should split the call.
   */
  private static final int MAX_TABLES_PER_BATCH = 100;

  private static final int MAX_TABLES_PER_DELETE = 50;
  private static final int MAX_RECORDS_PER_GET = 100;
  private static final int MAX_RECORDS_PER_DELETE = 500;

  final FeishuBitableService feishuBitableService;
  final FeishuDriveService feishuDriveService;
  final FeishuPermissionTools feishuPermissionTools;
  final FeishuUserFolders userFolders;
  final JsonMapper objectMapper;

  /** The reference pages this class hands back, in the workspace's language. */
  final FeishuGuides guides;

  @Builder
  @Jacksonized
  public static record CreatedBitable(String appToken, String url, String defaultTableId) {}

  @Builder
  @Jacksonized
  public static record BitableMeta(
      String appToken,
      String name,
      Integer revision,
      Boolean isAdvanced,
      String timeZone,
      Integer formulaType,
      String advanceVersion) {}

  @Builder
  @Jacksonized
  public static record UpdatedBitableMeta(
      String appToken, String name, Boolean isAdvanced, String timeZone) {}

  @Builder
  @Jacksonized
  public static record CreatedTable(String tableId, String defaultViewId, List<String> fieldIds) {}

  @Builder
  @Jacksonized
  public static record CreatedView(String viewId, String viewName, String viewType) {}

  @Tool(
      name = "FeishuCreateBitable",
      description =
          "Create a bitable (多维表格, a \"base\") in Feishu drive and return its token, URL and the"
              + " id of the table it starts with. That defaultTableId goes straight into the"
              + " tableId parameter of the record and view tools, with no call to"
              + " FeishuListBitableTables in between.\n"
              + "**Prefer this over FeishuCreateSpreadsheet whenever the data has structure the"
              + " user will want to query**: typed columns, a status to filter on, a person to"
              + " group by, a due date to sort by. A spreadsheet is the better answer only for a"
              + " flat dump of rows nobody will filter. Either way, when a list owed to the user"
              + " runs past ten rows, put it in a file and reply with nothing but the link.\n"
              + "The table it starts with has only a text column: shape it with"
              + " FeishuCreateBitableTable, or add the columns you need before writing records.")
  public CreatedBitable createBitable(
      @ToolParam(description = "Title of the new bitable") String title,
      @ToolParam(
              description =
                  "Token of the folder to create it in; the folder belonging to whoever you"
                      + " are talking to when left out",
              required = false)
          String folderToken,
      @ToolParam(
              description =
                  "IANA time zone the base reads its dates in, e.g. Asia/Shanghai; the tenant's"
                      + " default when left out",
              required = false)
          String timeZone,
      ToolContext toolContext) {

    final var targetFolderToken =
        folderToken == null || folderToken.isBlank()
            ? userFolders.folderFor(toolContext)
            : folderToken;

    final var app = feishuBitableService.createApp(targetFolderToken, title, timeZone);

    feishuPermissionTools.handOverToAsker(toolContext, app.getAppToken(), "bitable");

    return CreatedBitable.builder()
        .appToken(app.getAppToken())
        .url(app.getUrl())
        .defaultTableId(app.getDefaultTableId())
        .build();
  }

  @Tool(
      name = "FeishuGetBitableMeta",
      description =
          "Name, revision, time zone and advanced-permission state of a bitable. The appToken is"
              + " the token in a /base/<token> link, and FeishuGetWikiNodeInfo resolves a wiki link"
              + " to one.")
  public BitableMeta getBitableMeta(
      @ToolParam(description = "The app_token identifying the bitable") String appToken) {
    final var app = feishuBitableService.getApp(appToken);
    return BitableMeta.builder()
        .appToken(app.getAppToken())
        .name(app.getName())
        .revision(app.getRevision())
        .isAdvanced(app.getIsAdvanced())
        .timeZone(app.getTimeZone())
        .formulaType(app.getFormulaType())
        .advanceVersion(app.getAdvanceVersion())
        .build();
  }

  @Tool(
      name = "FeishuUpdateBitableMeta",
      description =
          "Rename a bitable, or switch its advanced permissions on or off.\n"
              + "The two are not applied together: the name goes first and the permission toggle"
              + " second, so a failure can leave the base renamed but the toggle unchanged. Read"
              + " the result back with FeishuGetBitableMeta before reporting either as done."
              + " Advanced permissions cannot be switched on for a base embedded in a document or"
              + " spreadsheet, nor for one that lives in a wiki.")
  public UpdatedBitableMeta updateBitableMeta(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The new name; left unchanged when left out", required = false)
          String name,
      @ToolParam(
              description = "Whether advanced permissions are on; left unchanged when left out",
              required = false)
          Boolean isAdvanced) {
    final var app = feishuBitableService.updateApp(appToken, name, isAdvanced);
    return UpdatedBitableMeta.builder()
        .appToken(app.getAppToken())
        .name(app.getName())
        .isAdvanced(app.getIsAdvanced())
        .timeZone(app.getTimeZone())
        .build();
  }

  @Tool(
      name = "FeishuListBitableTables",
      description =
          "The tables of a bitable, each with its table_id, name and revision. The table_id is what"
              + " every other bitable tool takes; the columns of one table come from"
              + " FeishuListBitableFields.")
  @SneakyThrows
  public JsonNode listBitableTables(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(
              description = "page_token of a previous page's result, to read the next one",
              required = false)
          String pageToken,
      @ToolParam(description = "Tables per page, 20 by default and 100 at most", required = false)
          Integer pageSize) {
    return objectMapper.readTree(feishuBitableService.listTables(appToken, pageToken, pageSize));
  }

  @Tool(
      name = "FeishuCreateBitableTable",
      description =
          "Add a table to a bitable, optionally with its columns and the name of its first view"
              + " already set.\n"
              + "fieldsJson and defaultViewName go together: pass both or neither. Each element of"
              + " fieldsJson is {\"field_name\": ..., \"type\": <number>, \"ui_type\": ...} and may"
              + " carry a property object — the options of a select field, the linked table of a"
              + " link field, and so on. FeishuBitableFieldReference has the types and the"
              + " properties each one takes.\n"
              + "A table name is 1 to 100 characters and may not contain / \\\\ ? * : [ or ]. A"
              + " bitable holds at most 100 tables and dashboards together. To add several tables"
              + " at once and name them only, FeishuBatchCreateBitableTables is one call instead of"
              + " many.")
  public CreatedTable createBitableTable(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "Name of the new table") String name,
      @ToolParam(
              description = "Name of the view it starts with; required if fieldsJson is given",
              required = false)
          String defaultViewName,
      @ToolParam(
              description =
                  "JSON array of the columns to create, of the shape"
                      + " FeishuBitableFieldReference describes; required if defaultViewName is"
                      + " given",
              required = false)
          String fieldsJson) {
    final var created =
        feishuBitableService.createTable(appToken, name, defaultViewName, fieldsJson);
    return CreatedTable.builder()
        .tableId(created.getTableId())
        .defaultViewId(created.getDefaultViewId())
        .fieldIds(created.getFieldIdList() == null ? null : List.of(created.getFieldIdList()))
        .build();
  }

  @Tool(
      name = "FeishuBatchCreateBitableTables",
      description =
          "Add several tables to a bitable in one call, named only — each one comes up with a"
              + " single text column. Use FeishuCreateBitableTable instead when a table needs its"
              + " columns defined up front. At most 100 in a call, and a bitable holds at most 100"
              + " tables and dashboards together.")
  public List<String> batchCreateBitableTables(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "Names of the tables to create") List<String> names) {
    if (names == null || names.isEmpty()) {
      return List.of();
    }
    if (names.size() > MAX_TABLES_PER_BATCH) {
      throw new IllegalArgumentException(
          "At most " + MAX_TABLES_PER_BATCH + " tables in one call, got " + names.size());
    }
    return feishuBitableService.batchCreateTables(appToken, names);
  }

  @Tool(
      name = "FeishuRenameBitableTable",
      description =
          "Rename a table of a bitable. An empty name, or the name it already has, comes back as a"
              + " success without renaming anything, so read the returned name rather than assuming"
              + " the one passed in took. The same character restrictions as"
              + " FeishuCreateBitableTable apply.")
  public String renameBitableTable(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table to rename") String tableId,
      @ToolParam(description = "The new name") String name) {
    return "Table "
        + tableId
        + " is now named '"
        + feishuBitableService.renameTable(appToken, tableId, name)
        + "'.";
  }

  @Tool(
      name = "FeishuDeleteBitableTables",
      description =
          "Delete tables from a bitable, with every record in them. **This cannot be undone**, so"
              + " pass only table ids the user named or that FeishuListBitableTables just returned,"
              + " and never a table you have not confirmed is the one meant. At most 50 in a call.")
  public String deleteBitableTables(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_ids of the tables to delete") List<String> tableIds) {
    if (tableIds == null || tableIds.isEmpty()) {
      return "There was nothing to delete.";
    }
    if (tableIds.size() > MAX_TABLES_PER_DELETE) {
      throw new IllegalArgumentException(
          "At most " + MAX_TABLES_PER_DELETE + " tables in one call, got " + tableIds.size());
    }
    feishuBitableService.batchDeleteTables(appToken, tableIds);
    return "Deleted " + tableIds.size() + " table(s) from bitable " + appToken + ".";
  }

  @Tool(
      name = "FeishuListBitableFields",
      description =
          "The columns of a table: each one's field_id, field_name, type, ui_type, property and"
              + " whether it is the primary column.\n"
              + "**Call this before writing records to a table you did not just create.** The"
              + " fieldsJson of FeishuCreateBitableRecord is keyed by field name and its values are"
              + " shaped by field type, and a name alone does not say whether a column is text, a"
              + " single select or a link — FeishuBitableFieldReference explains what each type"
              + " takes. It is also where the field_id that FeishuUpdateBitableField needs comes"
              + " from.")
  @SneakyThrows
  public JsonNode listBitableFields(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(
              description = "Only the fields visible in this view, and in its order",
              required = false)
          String viewId,
      @ToolParam(
              description = "page_token of a previous page's result, to read the next one",
              required = false)
          String pageToken,
      @ToolParam(description = "Fields per page, 20 by default and 100 at most", required = false)
          Integer pageSize) {
    return objectMapper.readTree(
        feishuBitableService.listFields(appToken, tableId, viewId, pageToken, pageSize));
  }

  @Tool(
      name = "FeishuListBitableViews",
      description =
          "The views of a table, each with its view_id, name, type (grid, kanban, gallery, gantt or"
              + " form) and how visible it is. A view_id narrows FeishuSearchBitableRecords to the"
              + " rows and columns that view shows.")
  @SneakyThrows
  public JsonNode listBitableViews(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(
              description = "page_token of a previous page's result, to read the next one",
              required = false)
          String pageToken,
      @ToolParam(description = "Views per page, 20 by default and 100 at most", required = false)
          Integer pageSize) {
    return objectMapper.readTree(
        feishuBitableService.listViews(appToken, tableId, pageToken, pageSize));
  }

  @Tool(
      name = "FeishuGetBitableView",
      description =
          "One view in full: its type, the fields it hides, its child-record hierarchy setting and"
              + " the filter it applies.\n"
              + "That filter is written differently from the one FeishuSearchBitableRecords takes —"
              + " it names each field by field_id and each condition's value is a single string,"
              + " not an array. Do not copy it into a search filter unchanged;"
              + " FeishuBitableFilterGuide has the search form.")
  @SneakyThrows
  public JsonNode getBitableView(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The view_id of the view") String viewId) {
    return objectMapper.readTree(feishuBitableService.getView(appToken, tableId, viewId));
  }

  @Tool(
      name = "FeishuCreateBitableView",
      description =
          "Add a view to a table: another way to look at the same records, filtered, grouped or"
              + " laid out differently. A table holds at most 200 views.\n"
              + "The view comes up unfiltered and showing everything — these tools do not cover"
              + " configuring a view afterwards, so what it filters or hides has to be set in the"
              + " Feishu UI.")
  public CreatedView createBitableView(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "Name of the new view, at most 100 characters and no [ or ]")
          String viewName,
      @ToolParam(
              description = "grid (the default), kanban, gallery, gantt or form",
              required = false)
          String viewType) {
    final var view = feishuBitableService.createView(appToken, tableId, viewName, viewType);
    return CreatedView.builder()
        .viewId(view.getViewId())
        .viewName(view.getViewName())
        .viewType(view.getViewType())
        .build();
  }

  @Tool(
      name = "FeishuDeleteBitableView",
      description =
          "Delete a view of a table. The records themselves are untouched — a view is only a way of"
              + " looking at them — but the view's own filters, grouping and layout are gone for"
              + " good.")
  public String deleteBitableView(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The view_id of the view to delete") String viewId) {
    feishuBitableService.deleteView(appToken, tableId, viewId);
    return "Deleted view " + viewId + " of table " + tableId + ".";
  }

  @Tool(
      name = "FeishuSearchBitableRecords",
      description =
          "**The way to read records**: filtered, sorted, and narrowed to the fields asked for."
              + " Returns the rows with their record_ids, whether more pages follow, and a"
              + " page_token to read them with.\n"
              + "Called with nothing but appToken and tableId it returns the first page of"
              + " everything, which is the right way to see what a table holds."
              + " FeishuBitableFilterGuide has the shape of filterJson and sortJson — read it"
              + " before writing either, since a condition's value is always an array of strings"
              + " and a date takes a token rather than a timestamp. Field types come from"
              + " FeishuListBitableFields.\n"
              + "At most 500 rows a page. To fetch rows whose ids you already know,"
              + " FeishuBatchGetBitableRecords is the cheaper call.")
  @SneakyThrows
  public JsonNode searchBitableRecords(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "Search within this view's rows and columns only", required = false)
          String viewId,
      @ToolParam(
              description = "Names of the fields to return; every field when left out",
              required = false)
          List<String> fieldNames,
      @ToolParam(
              description =
                  "The filter as JSON, of the shape FeishuBitableFilterGuide describes; unfiltered"
                      + " when left out",
              required = false)
          String filterJson,
      @ToolParam(
              description =
                  "JSON array of [{\"field_name\": ..., \"desc\": true|false}]; the table's own"
                      + " order when left out",
              required = false)
          String sortJson,
      @ToolParam(
              description =
                  "Also return created_time, last_modified_time, created_by and last_modified_by",
              required = false)
          Boolean automaticFields,
      @ToolParam(
              description = "page_token of a previous page's result, to read the next one",
              required = false)
          String pageToken,
      @ToolParam(description = "Rows per page, 20 by default and 500 at most", required = false)
          Integer pageSize) {
    return objectMapper.readTree(
        feishuBitableService.searchRecords(
            appToken,
            tableId,
            viewId,
            fieldNames,
            filterJson,
            sortJson,
            automaticFields,
            pageToken,
            pageSize));
  }

  @Tool(
      name = "FeishuBatchGetBitableRecords",
      description =
          "Fetch records by record_id, at most 100 in a call. Alongside the rows it returns the ids"
              + " it was not allowed to read and the ids that do not exist, so a missing row is"
              + " distinguishable from a forbidden one. Use FeishuSearchBitableRecords when the"
              + " rows are described by a condition rather than named by id.")
  @SneakyThrows
  public JsonNode batchGetBitableRecords(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The record_ids to fetch, at most 100") List<String> recordIds,
      @ToolParam(description = "Also return a shareable link to each record", required = false)
          Boolean withSharedUrl,
      @ToolParam(
              description =
                  "Also return created_time, last_modified_time, created_by and last_modified_by",
              required = false)
          Boolean automaticFields) {
    if (recordIds == null || recordIds.isEmpty()) {
      throw new IllegalArgumentException("recordIds must name at least one record");
    }
    if (recordIds.size() > MAX_RECORDS_PER_GET) {
      throw new IllegalArgumentException(
          "At most " + MAX_RECORDS_PER_GET + " records in one call, got " + recordIds.size());
    }
    return objectMapper.readTree(
        feishuBitableService.batchGetRecords(
            appToken, tableId, recordIds, withSharedUrl, automaticFields));
  }

  @Tool(
      name = "FeishuCreateBitableRecord",
      description =
          "Add one record to a table and return it with the record_id it was given.\n"
              + "fieldsJson is an object keyed by **field name**, and each value's shape follows"
              + " that field's type — a date is a millisecond timestamp, a person is [{\"id\":"
              + " \"ou_...\"}], a link is an array of record ids. FeishuBitableFieldReference has"
              + " them all, and FeishuListBitableFields says which type each column of this table"
              + " is. A field left out of fieldsJson is left empty rather than defaulted.\n"
              + "To add several records, FeishuBatchCreateBitableRecords is one call instead of"
              + " many.")
  @SneakyThrows
  public JsonNode createBitableRecord(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The record's fields as a JSON object keyed by field name")
          String fieldsJson,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot create twice",
              required = false)
          String clientToken,
      @ToolParam(
              description =
                  "Write even if the base changed since it was read, rather than failing the call",
              required = false)
          Boolean ignoreConsistencyCheck) {
    return objectMapper.readTree(
        feishuBitableService.createRecord(
            appToken, tableId, fieldsJson, clientToken, ignoreConsistencyCheck));
  }

  @Tool(
      name = "FeishuBatchCreateBitableRecords",
      description =
          "Add several records to a table in one call, at most 1000. **This is how a list of any"
              + " length gets written** — one call per row is both slower and rate limited."
              + " recordsJson is an array of {\"fields\": {...}} objects, each fields object of the"
              + " shape FeishuCreateBitableRecord takes.")
  @SneakyThrows
  public JsonNode batchCreateBitableRecords(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "JSON array of {\"fields\": {...}} objects, at most 1000")
          String recordsJson,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot create twice",
              required = false)
          String clientToken,
      @ToolParam(
              description =
                  "Write even if the base changed since it was read, rather than failing the call",
              required = false)
          Boolean ignoreConsistencyCheck) {
    return objectMapper.readTree(
        feishuBitableService.batchCreateRecords(
            appToken, tableId, recordsJson, clientToken, ignoreConsistencyCheck));
  }

  @Tool(
      name = "FeishuUpdateBitableRecord",
      description =
          "Overwrite fields of one record. Only the fields named in fieldsJson are touched; the"
              + " rest keep their values, and a field set to null is emptied. The shapes are the"
              + " ones FeishuCreateBitableRecord and FeishuBitableFieldReference describe. To"
              + " update several records, FeishuBatchUpdateBitableRecords is one call instead of"
              + " many.")
  @SneakyThrows
  public JsonNode updateBitableRecord(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The record_id of the record to update") String recordId,
      @ToolParam(description = "The fields to overwrite, as a JSON object keyed by field name")
          String fieldsJson,
      @ToolParam(
              description =
                  "Write even if the base changed since it was read, rather than failing the call",
              required = false)
          Boolean ignoreConsistencyCheck) {
    return objectMapper.readTree(
        feishuBitableService.updateRecord(
            appToken, tableId, recordId, fieldsJson, ignoreConsistencyCheck));
  }

  @Tool(
      name = "FeishuBatchUpdateBitableRecords",
      description =
          "Update several records of a table in one call, at most 1000. recordsJson is an array of"
              + " {\"record_id\": ..., \"fields\": {...}} objects — every element needs a"
              + " record_id, which FeishuSearchBitableRecords returns for each row.")
  @SneakyThrows
  public JsonNode batchUpdateBitableRecords(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(
              description =
                  "JSON array of {\"record_id\": ..., \"fields\": {...}} objects, at most 1000")
          String recordsJson,
      @ToolParam(
              description =
                  "Write even if the base changed since it was read, rather than failing the call",
              required = false)
          Boolean ignoreConsistencyCheck) {
    return objectMapper.readTree(
        feishuBitableService.batchUpdateRecords(
            appToken, tableId, recordsJson, ignoreConsistencyCheck));
  }

  @Tool(
      name = "FeishuDeleteBitableRecords",
      description =
          "Delete records from a table by record_id, at most 500 in a call. **This cannot be"
              + " undone**, so pass only ids the user named or that FeishuSearchBitableRecords just"
              + " returned for the rows they described, and read the rows back before deleting when"
              + " the request was described rather than enumerated.")
  public String deleteBitableRecords(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The record_ids to delete, at most 500") List<String> recordIds) {
    if (recordIds == null || recordIds.isEmpty()) {
      return "There was nothing to delete.";
    }
    if (recordIds.size() > MAX_RECORDS_PER_DELETE) {
      throw new IllegalArgumentException(
          "At most " + MAX_RECORDS_PER_DELETE + " records in one call, got " + recordIds.size());
    }
    feishuBitableService.batchDeleteRecords(appToken, tableId, recordIds);
    return "Deleted " + recordIds.size() + " record(s) from table " + tableId + ".";
  }

  @Tool(
      name = "FeishuUploadBitableAttachment",
      description =
          "Upload a local image or file into a bitable and return the file_token that an attachment"
              + " cell refers to it by. **This is step one of putting a picture or a file in a"
              + " record**; step two is writing [{\"file_token\": \"<what this returned>\"}]"
              + " into that field with FeishuCreateBitableRecord or one of the update tools.\n"
              + "The token belongs to the base it was uploaded into: one taken from another base,"
              + " from a document, or from an attachment cell read back earlier is refused, so"
              + " upload again rather than reusing a token. An attachment cell holds at most 100"
              + " files, each its own upload."
              + " FeishuBitableFieldReference has the rest of the field shapes.")
  public String uploadBitableAttachment(
      @ToolParam(
              description =
                  "The app_token of the bitable the attachment is destined for; the token is bound"
                      + " to it")
          String appToken,
      @ToolParam(description = "Absolute path of the local file") String filePath,
      @ToolParam(description = "Filename to show in the cell") String fileName,
      @ToolParam(
              description =
                  "bitable_image for an image, bitable_file for anything else; bitable_file when"
                      + " left out",
              required = false)
          String parentType) {
    final var file = new File(filePath);
    if (!file.isFile()) {
      throw new IllegalArgumentException(
          "filePath does not point to an existing file: " + filePath);
    }
    final var targetParentType =
        parentType == null || parentType.isBlank() ? "bitable_file" : parentType;
    if (!"bitable_image".equals(targetParentType) && !"bitable_file".equals(targetParentType)) {
      // Any other parent_type uploads the file somewhere a bitable cell then cannot refer to, and
      // Feishu reports that only later, as the record write failing.
      throw new IllegalArgumentException(
          "parentType must be bitable_image or bitable_file, got " + targetParentType);
    }
    return feishuDriveService.uploadMedia(fileName, targetParentType, appToken, file);
  }

  @Tool(
      name = "FeishuCreateBitableField",
      description =
          "Add a column to a table and return it with the field_id it was given. This is how a"
              + " table gets the shape the data needs — a status to filter on, an owner, a due"
              + " date, an attachment cell — when it was not defined up front with"
              + " FeishuCreateBitableTable.\n"
              + "type is the numeric field type: 1 text, 2 number, 3 single select, 4 multi select,"
              + " 5 date, 7 checkbox, 11 person, 13 phone, 15 url, 17 attachment, 18 single link,"
              + " 20 formula, 21 duplex link, 22 location, 23 group chat, 1001 created time, 1002"
              + " last modified time, 1003 created by, 1004 last modified by, 1005 auto number. A"
              + " lookup field (19) cannot be created. uiType narrows some of them — a progress bar"
              + " and a rating are both type 2 — and propertyJson carries the column's own"
              + " settings, both as FeishuBitableFieldReference describes.\n"
              + "A field name has to be unique within the table, and its leading and trailing"
              + " spaces are stripped.")
  public JsonNode createBitableField(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "Name of the new column, unique within the table") String fieldName,
      @ToolParam(description = "Numeric field type; FeishuBitableFieldReference lists them")
          Integer type,
      @ToolParam(
              description =
                  "How it shows, e.g. Progress or Rating for a type 2; the type's default when"
                      + " left out",
              required = false)
          String uiType,
      @ToolParam(
              description =
                  "The column's settings as JSON — select options, a link's target table, a date"
                      + " format — of the shape FeishuBitableFieldReference describes",
              required = false)
          String propertyJson,
      @ToolParam(description = "Description shown on the column", required = false)
          String description,
      @ToolParam(
              description =
                  "Whether to keep the description off the matching question of a form built on"
                      + " this table; true when left out",
              required = false)
          Boolean disableDescriptionSync,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot create twice",
              required = false)
          String clientToken) {
    return fieldNode(
        feishuBitableService.createField(
            appToken,
            tableId,
            fieldName,
            type,
            uiType,
            propertyJson,
            description,
            disableDescriptionSync,
            clientToken));
  }

  @Tool(
      name = "FeishuUpdateBitableField",
      description =
          "Change a column of a table: its name, its type, or its settings.\n"
              + "**This overwrites the field rather than patching it.** Whatever is left out here"
              + " is cleared on the column, not kept — dropping propertyJson while changing a name"
              + " loses a select field's options and a link field's target table. So read the"
              + " column with FeishuListBitableFields first and send its whole property back with"
              + " the one part changed. fieldName and type are both required for the same reason.\n"
              + "Changing the type converts the values already in the column, and what does not"
              + " convert is lost. A lookup field (19) cannot be updated, and the table's first"
              + " column may only be a text, number, date, phone, url, formula or location field.")
  public JsonNode updateBitableField(
      @ToolParam(description = "The app_token identifying the bitable") String appToken,
      @ToolParam(description = "The table_id of the table") String tableId,
      @ToolParam(description = "The field_id of the column, from FeishuListBitableFields")
          String fieldId,
      @ToolParam(description = "Name the column should have afterwards") String fieldName,
      @ToolParam(description = "Numeric field type the column should have afterwards") Integer type,
      @ToolParam(description = "How it shows; the type's default when left out", required = false)
          String uiType,
      @ToolParam(
              description =
                  "The column's settings as JSON, in full — anything left out is cleared, not kept",
              required = false)
          String propertyJson,
      @ToolParam(
              description = "Description shown on the column; cleared when left out",
              required = false)
          String description,
      @ToolParam(
              description =
                  "Whether to keep the description off the matching question of a form built on"
                      + " this table; true when left out",
              required = false)
          Boolean disableDescriptionSync) {
    return fieldNode(
        feishuBitableService.updateField(
            appToken,
            tableId,
            fieldId,
            fieldName,
            type,
            uiType,
            propertyJson,
            description,
            disableDescriptionSync));
  }

  @Tool(
      name = "FeishuBitableFieldReference",
      description =
          "What a bitable field of each type holds, and how a record's fields object is written for"
              + " it. Read this before writing records or defining columns: several types read back"
              + " in a different shape than they are written in, and getting one wrong fails the"
              + " whole write.")
  public String getBitableFieldReference() {
    return guides.bitableFieldReference();
  }

  @Tool(
      name = "FeishuBitableFilterGuide",
      description =
          "How to write the filterJson and sortJson of FeishuSearchBitableRecords: the conjunction,"
              + " the conditions, the one level of nesting that mixes and with or, the operators"
              + " each field type allows, and the date tokens. Read it before filtering a search.")
  public String getBitableFilterGuide() {
    return guides.bitableFilterGuide();
  }

  /**
   * A field as the model sees it. The SDK type is passed through as a tree rather than mapped onto
   * a record of our own because {@code property} differs per field type and any record would either
   * flatten it or have to enumerate every type's settings.
   */
  @SneakyThrows
  private JsonNode fieldNode(final AppTableField field) {
    return objectMapper.readTree(Jsons.DEFAULT.toJson(field));
  }
}
