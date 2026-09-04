package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.bitable.v1.model.App;
import com.lark.oapi.service.bitable.v1.model.AppTableField;
import com.lark.oapi.service.bitable.v1.model.AppTableFieldProperty;
import com.lark.oapi.service.bitable.v1.model.AppTableView;
import com.lark.oapi.service.bitable.v1.model.CreateAppTableRespBody;
import com.lark.oapi.service.bitable.v1.model.DisplayApp;
import com.lark.oapi.service.bitable.v1.model.DisplayAppV2;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.V1;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberResp;
import com.lark.oapi.service.drive.v1.resource.PermissionMember;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.bitable.FeishuBitableService;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class FeishuBitableToolsTest {

  @Mock private FeishuBitableService feishuBitableService;
  @Mock private FeishuDriveService feishuDriveService;
  @Mock private Client feishu;
  @Mock private DriveService driveService;
  @Mock private V1 driveV1;
  @Mock private PermissionMember permissionMember;
  @Mock private FeishuUserFolders userFolders;

  private FeishuBitableTools tools;

  private static final ToolContext TOOL_CONTEXT =
      new ToolContext(
          Map.of(
              ToolContexts.KEY_USER_ID,
              "user1",
              ToolContexts.KEY_CHAT_ID,
              "chat1",
              ToolContexts.KEY_CHAT_TYPE,
              "p2p"));

  @BeforeEach
  void setUp() throws Exception {
    lenient()
        .when(userFolders.folderFor(org.mockito.ArgumentMatchers.any()))
        .thenReturn("ou_userOwnFolder");
    lenient().when(feishu.drive()).thenReturn(driveService);
    lenient().when(driveService.v1()).thenReturn(driveV1);
    lenient().when(driveV1.permissionMember()).thenReturn(permissionMember);
    lenient()
        .when(permissionMember.batchCreate(any()))
        .thenReturn(new BatchCreatePermissionMemberResp());

    tools =
        new FeishuBitableTools(
            feishuBitableService,
            feishuDriveService,
            new FeishuPermissionTools(feishu),
            userFolders,
            new JsonMapper(),
            new FeishuGuides(null));
  }

  @Test
  @DisplayName("createBitable falls back to the folder belonging to the requester")
  void createBitableUsesTheRequestersOwnFolder() {
    stubAppCreation();

    tools.createBitable("My Base", null, null, TOOL_CONTEXT);

    verify(feishuBitableService).createApp("ou_userOwnFolder", "My Base", null);
  }

  @Test
  @DisplayName("createBitable returns the token, url and starting table of the new base")
  void createBitableReturnsTheCreatedBase() {
    stubAppCreation();

    final var result = tools.createBitable("My Base", "folderToken", "Asia/Shanghai", TOOL_CONTEXT);

    assertThat(result.appToken()).isEqualTo("appToken");
    assertThat(result.url()).isEqualTo("https://example.feishu.cn/base/appToken");
    assertThat(result.defaultTableId()).isEqualTo("tblDefault");
    verify(feishuBitableService).createApp("folderToken", "My Base", "Asia/Shanghai");
  }

  @Test
  @DisplayName("createBitable grants the requesting user full_access on the base after creation")
  void createBitableGrantsPermissions() throws Exception {
    stubAppCreation();

    tools.createBitable("My Base", "folderToken", null, TOOL_CONTEXT);

    final var captor = org.mockito.ArgumentCaptor.forClass(BatchCreatePermissionMemberReq.class);
    verify(permissionMember).batchCreate(captor.capture());
    final var req = captor.getValue();
    assertThat(req.getToken()).isEqualTo("appToken");
    assertThat(req.getType()).isEqualTo("bitable");
    assertThat(req.getBatchCreatePermissionMemberReqBody().getMembers()).hasSize(1);
    final var member = req.getBatchCreatePermissionMemberReqBody().getMembers()[0];
    assertThat(member.getMemberId()).isEqualTo("user1");
    assertThat(member.getPerm()).isEqualTo("full_access");
  }

  @Test
  @DisplayName("createBitable still returns the created base if granting permissions fails")
  void createBitableSucceedsEvenWhenPermissionGrantFails() throws Exception {
    stubAppCreation();
    when(permissionMember.batchCreate(any())).thenThrow(new RuntimeException("network error"));

    final var result = tools.createBitable("My Base", "folderToken", null, TOOL_CONTEXT);

    assertThat(result.appToken()).isEqualTo("appToken");
  }

  @Test
  @DisplayName("getBitableMeta maps the metadata Feishu returns onto the tool's own record")
  void getBitableMeta() {
    final var app = new DisplayApp();
    app.setAppToken("appToken");
    app.setName("My Base");
    app.setRevision(7);
    app.setIsAdvanced(true);
    app.setTimeZone("Asia/Shanghai");
    app.setFormulaType(2);
    app.setAdvanceVersion("v2");
    when(feishuBitableService.getApp("appToken")).thenReturn(app);

    final var result = tools.getBitableMeta("appToken");

    assertThat(result.name()).isEqualTo("My Base");
    assertThat(result.revision()).isEqualTo(7);
    assertThat(result.isAdvanced()).isTrue();
    assertThat(result.advanceVersion()).isEqualTo("v2");
  }

  @Test
  @DisplayName("updateBitableMeta returns what the base looks like afterwards, not what was asked")
  void updateBitableMeta() {
    final var app = new DisplayAppV2();
    app.setAppToken("appToken");
    app.setName("Renamed");
    app.setIsAdvanced(false);
    when(feishuBitableService.updateApp("appToken", "Renamed", true)).thenReturn(app);

    final var result = tools.updateBitableMeta("appToken", "Renamed", true);

    // The toggle is applied after the rename and can fail on its own, so the tool reports the
    // state Feishu came back with rather than echoing isAdvanced=true.
    assertThat(result.name()).isEqualTo("Renamed");
    assertThat(result.isAdvanced()).isFalse();
  }

  @Test
  @DisplayName("listBitableTables parses the service's JSON into a tree")
  void listBitableTables() {
    when(feishuBitableService.listTables("appToken", null, null))
        .thenReturn("{\"has_more\":false,\"items\":[{\"table_id\":\"tblA\",\"name\":\"Tasks\"}]}");

    final var result = tools.listBitableTables("appToken", null, null);

    assertThat(result.path("items").get(0).path("table_id").asString()).isEqualTo("tblA");
  }

  @Test
  @DisplayName("createBitableTable returns the new table's id, view and field ids")
  void createBitableTable() {
    final var body = new CreateAppTableRespBody();
    body.setTableId("tblA");
    body.setDefaultViewId("vewA");
    body.setFieldIdList(new String[] {"fldA", "fldB"});
    when(feishuBitableService.createTable("appToken", "Tasks", "Grid", "[]")).thenReturn(body);

    final var result = tools.createBitableTable("appToken", "Tasks", "Grid", "[]");

    assertThat(result.tableId()).isEqualTo("tblA");
    assertThat(result.defaultViewId()).isEqualTo("vewA");
    assertThat(result.fieldIds()).containsExactly("fldA", "fldB");
  }

  @Test
  @DisplayName("createBitableTable leaves the field ids null when no columns were defined")
  void createBitableTableWithoutFields() {
    final var body = new CreateAppTableRespBody();
    body.setTableId("tblA");
    when(feishuBitableService.createTable("appToken", "Tasks", null, null)).thenReturn(body);

    final var result = tools.createBitableTable("appToken", "Tasks", null, null);

    assertThat(result.fieldIds()).isNull();
  }

  @Test
  @DisplayName("batchCreateBitableTables calls nothing at all when given no names")
  void batchCreateBitableTablesWithNoNames() {
    assertThat(tools.batchCreateBitableTables("appToken", List.of())).isEmpty();

    verifyNoInteractions(feishuBitableService);
  }

  @Test
  @DisplayName("batchCreateBitableTables refuses more tables than one call takes")
  void batchCreateBitableTablesOverTheLimit() {
    final var names = IntStream.range(0, 101).mapToObj(String::valueOf).toList();

    assertThatThrownBy(() -> tools.batchCreateBitableTables("appToken", names))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 100 tables");
    verify(feishuBitableService, never()).batchCreateTables(any(), any());
  }

  @Test
  @DisplayName("renameBitableTable reports the name Feishu ended up with")
  void renameBitableTable() {
    // Feishu answers a no-op rename with a success, so the tool has to report what came back
    // rather than the name it was asked for.
    when(feishuBitableService.renameTable("appToken", "tblA", "")).thenReturn("Tasks");

    final var message = tools.renameBitableTable("appToken", "tblA", "");

    assertThat(message).isEqualTo("Table tblA is now named 'Tasks'.");
  }

  @Test
  @DisplayName("deleteBitableTables refuses more tables than one call takes")
  void deleteBitableTablesOverTheLimit() {
    final var tableIds = IntStream.range(0, 51).mapToObj(String::valueOf).toList();

    assertThatThrownBy(() -> tools.deleteBitableTables("appToken", tableIds))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 50 tables");
    verify(feishuBitableService, never()).batchDeleteTables(any(), any());
  }

  @Test
  @DisplayName("deleteBitableTables deletes nothing when given no ids")
  void deleteBitableTablesWithNoIds() {
    assertThat(tools.deleteBitableTables("appToken", null))
        .isEqualTo("There was nothing to delete.");

    verifyNoInteractions(feishuBitableService);
  }

  @Test
  @DisplayName("deleteBitableTables delegates and says how many went")
  void deleteBitableTables() {
    final var message = tools.deleteBitableTables("appToken", List.of("tblA", "tblB"));

    assertThat(message).isEqualTo("Deleted 2 table(s) from bitable appToken.");
    verify(feishuBitableService).batchDeleteTables("appToken", List.of("tblA", "tblB"));
  }

  @Test
  @DisplayName("listBitableFields passes the view and paging straight through")
  void listBitableFields() {
    when(feishuBitableService.listFields("appToken", "tblA", "vewA", "page2", 50))
        .thenReturn("{\"items\":[{\"field_name\":\"Status\",\"type\":3}]}");

    final var result = tools.listBitableFields("appToken", "tblA", "vewA", "page2", 50);

    assertThat(result.path("items").get(0).path("field_name").asString()).isEqualTo("Status");
  }

  @Test
  @DisplayName("createBitableField returns the column with the field_id it was given")
  void createBitableField() {
    final var field = new AppTableField();
    field.setFieldId("fldA");
    field.setFieldName("Status");
    field.setType(3);
    when(feishuBitableService.createField(
            "appToken",
            "tblA",
            "Status",
            3,
            "SingleSelect",
            "{\"options\":[{\"name\":\"Doing\"}]}",
            "what stage it is at",
            null,
            "uuid"))
        .thenReturn(field);

    final var result =
        tools.createBitableField(
            "appToken",
            "tblA",
            "Status",
            3,
            "SingleSelect",
            "{\"options\":[{\"name\":\"Doing\"}]}",
            "what stage it is at",
            null,
            "uuid");

    assertThat(result.path("field_id").asString()).isEqualTo("fldA");
    assertThat(result.path("type").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("updateBitableField passes the whole property through, since an update overwrites")
  void updateBitableField() {
    final var field = new AppTableField();
    field.setFieldId("fldA");
    field.setFieldName("Owner");
    field.setType(11);
    final var property = new AppTableFieldProperty();
    property.setMultiple(true);
    field.setProperty(property);
    when(feishuBitableService.updateField(
            "appToken", "tblA", "fldA", "Owner", 11, null, "{\"multiple\":true}", null, null))
        .thenReturn(field);

    final var result =
        tools.updateBitableField(
            "appToken", "tblA", "fldA", "Owner", 11, null, "{\"multiple\":true}", null, null);

    assertThat(result.path("field_name").asString()).isEqualTo("Owner");
    assertThat(result.path("property").path("multiple").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("createBitableView returns the new view's id, name and type")
  void createBitableView() {
    final var view = new AppTableView();
    view.setViewId("vewA");
    view.setViewName("Board");
    view.setViewType("kanban");
    when(feishuBitableService.createView("appToken", "tblA", "Board", "kanban")).thenReturn(view);

    final var result = tools.createBitableView("appToken", "tblA", "Board", "kanban");

    assertThat(result.viewId()).isEqualTo("vewA");
    assertThat(result.viewType()).isEqualTo("kanban");
  }

  @Test
  @DisplayName("deleteBitableView delegates and names what it removed")
  void deleteBitableView() {
    final var message = tools.deleteBitableView("appToken", "tblA", "vewA");

    assertThat(message).isEqualTo("Deleted view vewA of table tblA.");
    verify(feishuBitableService).deleteView("appToken", "tblA", "vewA");
  }

  @Test
  @DisplayName("searchBitableRecords hands every search argument to the service unchanged")
  void searchBitableRecords() {
    final var filterJson =
        "{\"conjunction\":\"and\",\"conditions\":[{\"field_name\":\"Status\","
            + "\"operator\":\"is\",\"value\":[\"Doing\"]}]}";
    when(feishuBitableService.searchRecords(
            "appToken",
            "tblA",
            "vewA",
            List.of("Title"),
            filterJson,
            "[{\"field_name\":\"Due\"}]",
            true,
            "page2",
            500))
        .thenReturn("{\"items\":[{\"record_id\":\"recA\"}],\"has_more\":false}");

    final var result =
        tools.searchBitableRecords(
            "appToken",
            "tblA",
            "vewA",
            List.of("Title"),
            filterJson,
            "[{\"field_name\":\"Due\"}]",
            true,
            "page2",
            500);

    assertThat(result.path("items").get(0).path("record_id").asString()).isEqualTo("recA");
  }

  @Test
  @DisplayName("batchGetBitableRecords insists on at least one record id")
  void batchGetBitableRecordsWithNoIds() {
    assertThatThrownBy(
            () -> tools.batchGetBitableRecords("appToken", "tblA", List.of(), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one record");
  }

  @Test
  @DisplayName("batchGetBitableRecords refuses more records than one call takes")
  void batchGetBitableRecordsOverTheLimit() {
    final var recordIds = IntStream.range(0, 101).mapToObj(String::valueOf).toList();

    assertThatThrownBy(
            () -> tools.batchGetBitableRecords("appToken", "tblA", recordIds, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 100 records");
  }

  @Test
  @DisplayName("createBitableRecord returns the record Feishu created, with its new id")
  void createBitableRecord() {
    when(feishuBitableService.createRecord(
            "appToken", "tblA", "{\"Title\":\"Ship it\"}", "uuid", null))
        .thenReturn("{\"record_id\":\"recA\",\"fields\":{\"Title\":\"Ship it\"}}");

    final var result =
        tools.createBitableRecord("appToken", "tblA", "{\"Title\":\"Ship it\"}", "uuid", null);

    assertThat(result.path("record_id").asString()).isEqualTo("recA");
  }

  @Test
  @DisplayName("updateBitableRecord passes the record id and the fields to overwrite through")
  void updateBitableRecord() {
    when(feishuBitableService.updateRecord("appToken", "tblA", "recA", "{\"Done\":true}", true))
        .thenReturn("{\"record_id\":\"recA\",\"fields\":{\"Done\":true}}");

    final var result =
        tools.updateBitableRecord("appToken", "tblA", "recA", "{\"Done\":true}", true);

    assertThat(result.path("fields").path("Done").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("batchUpdateBitableRecords parses the service's JSON into a tree")
  void batchUpdateBitableRecords() {
    when(feishuBitableService.batchUpdateRecords("appToken", "tblA", "[]", null))
        .thenReturn("{\"records\":[{\"record_id\":\"recA\"}]}");

    final var result = tools.batchUpdateBitableRecords("appToken", "tblA", "[]", null);

    assertThat(result.path("records").get(0).path("record_id").asString()).isEqualTo("recA");
  }

  @Test
  @DisplayName("deleteBitableRecords refuses more records than one call takes")
  void deleteBitableRecordsOverTheLimit() {
    final var recordIds = IntStream.range(0, 501).mapToObj(String::valueOf).toList();

    assertThatThrownBy(() -> tools.deleteBitableRecords("appToken", "tblA", recordIds))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 500 records");
    verify(feishuBitableService, never()).batchDeleteRecords(any(), any(), any());
  }

  @Test
  @DisplayName("deleteBitableRecords deletes nothing when given no ids")
  void deleteBitableRecordsWithNoIds() {
    assertThat(tools.deleteBitableRecords("appToken", "tblA", List.of()))
        .isEqualTo("There was nothing to delete.");

    verifyNoInteractions(feishuBitableService);
  }

  @Test
  @DisplayName("batchCreateBitableRecords passes the records JSON through untouched")
  void batchCreateBitableRecords() {
    // The 1000-record limit lives in the service, which is the only place the count is known: a
    // tool is handed the records as JSON and cannot count them without parsing.
    when(feishuBitableService.batchCreateRecords(
            "appToken", "tblA", "[{\"fields\":{\"Title\":\"a\"}}]", null, null))
        .thenReturn("{\"records\":[{\"record_id\":\"recA\"}]}");

    final var result =
        tools.batchCreateBitableRecords(
            "appToken", "tblA", "[{\"fields\":{\"Title\":\"a\"}}]", null, null);

    assertThat(result.path("records").get(0).path("record_id").asString()).isEqualTo("recA");
  }

  @Test
  @DisplayName("uploadBitableAttachment uploads against the base itself and defaults to a file")
  void uploadBitableAttachment(@TempDir final java.nio.file.Path directory) throws Exception {
    final var file = Files.writeString(directory.resolve("shot.png"), "x");
    when(feishuDriveService.uploadMedia(
            "shot.png", "bitable_file", "appToken", new File(file.toString())))
        .thenReturn("boxcnabc");

    final var token = tools.uploadBitableAttachment("appToken", file.toString(), "shot.png", null);

    assertThat(token).isEqualTo("boxcnabc");
  }

  @Test
  @DisplayName("uploadBitableAttachment refuses a parent type a bitable cell cannot refer to")
  void uploadBitableAttachmentWithAForeignParentType(@TempDir final java.nio.file.Path directory)
      throws Exception {
    final var file = Files.writeString(directory.resolve("shot.png"), "x");

    // docx_image would upload happily and only fail later, as the record write rejecting the token.
    assertThatThrownBy(
            () ->
                tools.uploadBitableAttachment(
                    "appToken", file.toString(), "shot.png", "docx_image"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bitable_image or bitable_file");
    verifyNoInteractions(feishuDriveService);
  }

  @Test
  @DisplayName("uploadBitableAttachment says so when the path is not a file, rather than uploading")
  void uploadBitableAttachmentWithAMissingFile() {
    assertThatThrownBy(
            () -> tools.uploadBitableAttachment("appToken", "/nope/shot.png", "shot.png", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing file");
    verifyNoInteractions(feishuDriveService);
  }

  @Test
  @DisplayName("the guides name the shapes a record write and a search filter actually take")
  void guides() {
    assertThat(tools.getBitableFieldReference())
        .contains("link_record_ids")
        .contains("milliseconds")
        .contains("FeishuUploadBitableAttachment")
        .contains("auto_serial")
        .contains("FeishuCreateBitableField");
    assertThat(tools.getBitableFilterGuide())
        .contains("conjunction")
        .contains("ExactDate")
        .contains("array of strings");
  }

  private void stubAppCreation() {
    final var app = new App();
    app.setAppToken("appToken");
    app.setUrl("https://example.feishu.cn/base/appToken");
    app.setDefaultTableId("tblDefault");
    lenient().when(feishuBitableService.createApp(any(), any(), any())).thenReturn(app);
  }
}
