package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.V1;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberResp;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberResp;
import com.lark.oapi.service.drive.v1.resource.PermissionMember;
import com.lark.oapi.service.sheets.SheetsService;
import com.lark.oapi.service.sheets.v3.V3;
import com.lark.oapi.service.sheets.v3.model.CreateSpreadsheetResp;
import com.lark.oapi.service.sheets.v3.model.CreateSpreadsheetRespBody;
import com.lark.oapi.service.sheets.v3.model.QuerySpreadsheetSheetResp;
import com.lark.oapi.service.sheets.v3.model.QuerySpreadsheetSheetRespBody;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetValueRangeBatchDTOV2;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.GetValueRangeDTOV2;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.ProtectedRange;
import me.kezhenxu94.springagent.integration.feishu.model.spreadsheet.Sheet;
import me.kezhenxu94.springagent.integration.feishu.sheet.FeishuSheetsService;
import me.kezhenxu94.springagent.integration.feishu.sheet.ValueRange;
import me.kezhenxu94.springagent.integration.feishu.sheet.ValueRangeV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

@ExtendWith(MockitoExtension.class)
class FeishuSheetToolsTest {

  @Mock private FeishuSheetsService feishuSheetsService;
  @Mock private Client feishu;
  @Mock private DriveService driveService;
  @Mock private V1 driveV1;
  @Mock private PermissionMember permissionMember;
  @Mock private SheetsService sheetsService;
  @Mock private V3 sheetsV3;
  @Mock private com.lark.oapi.service.sheets.v3.resource.Spreadsheet spreadsheetResource;
  @Mock private com.lark.oapi.service.sheets.v3.resource.SpreadsheetSheet spreadsheetSheetResource;
  @Mock private FeishuUserFolders userFolders;

  private FeishuSheetTools tools;

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
        .when(permissionMember.batchCreate(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new BatchCreatePermissionMemberResp());
    lenient()
        .when(permissionMember.transferOwner(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new TransferOwnerPermissionMemberResp());
    lenient().when(feishu.sheets()).thenReturn(sheetsService);
    lenient().when(sheetsService.v3()).thenReturn(sheetsV3);
    lenient().when(sheetsV3.spreadsheet()).thenReturn(spreadsheetResource);
    lenient().when(sheetsV3.spreadsheetSheet()).thenReturn(spreadsheetSheetResource);

    tools =
        new FeishuSheetTools(
            feishu,
            feishuSheetsService,
            new FeishuPermissionTools(feishu, new FeishuDriveService(feishu, new JsonMapper())),
            userFolders,
            new JsonMapper(),
            new FeishuGuides(null));
  }

  @Test
  @DisplayName("listSheets delegates to FeishuSheetsService.getSheets")
  void listSheets() {
    final var sheet = Sheet.builder().sheetId("sxj5ws").title("Sheet1").index(0).build();
    when(feishuSheetsService.getSheets("token")).thenReturn(List.of(sheet));

    final var result = tools.listSheets("token");

    assertThat(result).containsExactly(sheet);
  }

  @Test
  @DisplayName("addSheet delegates to FeishuSheetsService.addSheet and returns the summary")
  void addSheet() {
    final var summary =
        FeishuSheetsService.SheetSummary.builder()
            .sheetId("l8Gddg")
            .title("new_sheet")
            .index(1)
            .build();
    when(feishuSheetsService.addSheet("token", "new_sheet", 1)).thenReturn(summary);

    final var result = tools.addSheet("token", "new_sheet", 1);

    assertThat(result).isEqualTo(summary);
  }

  @Test
  @DisplayName("copySheet delegates to FeishuSheetsService.copySheet and returns the summary")
  void copySheet() {
    final var summary =
        FeishuSheetsService.SheetSummary.builder()
            .sheetId("dso4jn")
            .title("sheet_copy")
            .index(0)
            .build();
    when(feishuSheetsService.copySheet("token", "sxj5ws", "sheet_copy")).thenReturn(summary);

    final var result = tools.copySheet("token", "sxj5ws", "sheet_copy");

    assertThat(result).isEqualTo(summary);
  }

  @Test
  @DisplayName("deleteSheet delegates to FeishuSheetsService.deleteSheet")
  void deleteSheet() {
    final var message = tools.deleteSheet("token", "sxj5ws");

    assertThat(message).isEqualTo("Deleted sheet sxj5ws.");
    verify(feishuSheetsService).deleteSheet("token", "sxj5ws");
  }

  @Test
  @DisplayName("readSheetRange converts JsonNode cells into plain values")
  void readSheetRange() {
    final var range =
        ValueRange.Range.builder()
            .sheetId("sxj5ws")
            .rowStart(1)
            .rowEnd(2)
            .columnStart(1)
            .columnEnd(2)
            .build();
    final var valueRange =
        ValueRangeV2.builder()
            .range(range)
            .values(List.of(List.of(StringNode.valueOf("a"), StringNode.valueOf("1"))))
            .build();
    when(feishuSheetsService.getRangeValuesV2(eq("token"), any()))
        .thenReturn(
            GetValueRangeDTOV2.builder()
                .revision(1)
                .spreadsheetToken("token")
                .valueRange(valueRange)
                .build());

    final var result = tools.readSheetRange("token", "sxj5ws!A1:B2");

    assertThat(result.range()).isEqualTo("sxj5ws!A1:B2");
    assertThat(result.values()).containsExactly(List.of("a", "1"));
  }

  @Test
  @DisplayName("batchReadSheetRanges parses every range and returns one entry per range")
  void batchReadSheetRanges() {
    final var range =
        ValueRange.Range.builder()
            .sheetId("sxj5ws")
            .rowStart(1)
            .rowEnd(1)
            .columnStart(1)
            .columnEnd(1)
            .build();
    final var valueRange =
        ValueRangeV2.builder()
            .range(range)
            .values(List.of(List.of(StringNode.valueOf("x"))))
            .build();
    when(feishuSheetsService.getRangeValuesBatchV2(eq("token"), anyList()))
        .thenReturn(
            GetValueRangeBatchDTOV2.builder()
                .revision(1)
                .spreadsheetToken("token")
                .totalCells(1)
                .valueRanges(List.of(valueRange))
                .build());

    final var result = tools.batchReadSheetRanges("token", List.of("sxj5ws!A1:A1"));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).range()).isEqualTo("sxj5ws!A1:A1");
    assertThat(result.get(0).values()).containsExactly(List.of("x"));
  }

  @Test
  @DisplayName(
      "batchReadSheetRanges returns an empty list without calling the service for null/empty input")
  void batchReadSheetRangesNoRanges() {
    assertThat(tools.batchReadSheetRanges("token", null)).isEmpty();
    assertThat(tools.batchReadSheetRanges("token", List.of())).isEmpty();
    verify(feishuSheetsService, org.mockito.Mockito.never()).getRangeValuesBatchV2(any(), any());
  }

  @Test
  @DisplayName("updateSheetRange returns early without calling the service when values are empty")
  void updateSheetRangeNoData() {
    final var message = tools.updateSheetRange("token", "sxj5ws!A1:A1", List.of());

    assertThat(message).isEqualTo("There was nothing to write.");
    verify(feishuSheetsService, org.mockito.Mockito.never()).setValuesV2(any(), any());
  }

  @Test
  @DisplayName("updateSheetRange writes the parsed range and values")
  void updateSheetRange() {
    final var message = tools.updateSheetRange("token", "sxj5ws!A1:B1", List.of(List.of("a", "b")));

    assertThat(message).isEqualTo("Wrote 1 rows to sxj5ws!A1:B1.");
    verify(feishuSheetsService).setValuesV2(eq("token"), any(ValueRangeV2.class));
  }

  @Test
  @DisplayName("batchUpdateSheetRanges writes every range")
  void batchUpdateSheetRanges() {
    final var message =
        tools.batchUpdateSheetRanges(
            "token",
            List.of(
                FeishuSheetTools.RangeValues.builder()
                    .range("sxj5ws!A1:A1")
                    .values(List.of(List.of("a")))
                    .build(),
                FeishuSheetTools.RangeValues.builder()
                    .range("sxj5ws!B1:B1")
                    .values(List.of(List.of("b")))
                    .build()));

    assertThat(message).isEqualTo("Wrote 2 ranges.");
    verify(feishuSheetsService).setValuesBatchV2(eq("token"), anyList());
  }

  @Test
  @DisplayName(
      "updateSheetRange passes rich cell objects (e.g. formula) through as JSON, not stringified")
  void updateSheetRangeWithFormula() {
    tools.updateSheetRange(
        "token", "sxj5ws!A1:A1", List.of(List.of(Map.of("type", "formula", "text", "=A1"))));

    final var captor = org.mockito.ArgumentCaptor.forClass(ValueRangeV2.class);
    verify(feishuSheetsService).setValuesV2(eq("token"), captor.capture());
    final var cell = captor.getValue().values().get(0).get(0);
    assertThat(cell.path("type").asString()).isEqualTo("formula");
    assertThat(cell.path("text").asString()).isEqualTo("=A1");
  }

  @Test
  @DisplayName(
      "setRangeStyle returns early without calling the service when no style fields are set")
  void setRangeStyleNoData() {
    final var message =
        tools.setRangeStyle(
            "token",
            "sxj5ws!A1:A1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(message).isEqualTo("There was no style to set.");
    verify(feishuSheetsService, org.mockito.Mockito.never()).setStyle(any(), any(), any());
  }

  @Test
  @DisplayName("setRangeStyle nests font fields and passes through top-level style fields")
  void setRangeStyle() {
    final var message =
        tools.setRangeStyle(
            "token",
            "sxj5ws!A1:C4",
            true,
            true,
            "10pt",
            0,
            "yyyy/MM/dd",
            0,
            0,
            "#000000",
            "#21D11F",
            "FULL_BORDER",
            "#FF0000",
            null);

    assertThat(message).isEqualTo("Styled sxj5ws!A1:C4.");
    final var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(feishuSheetsService)
        .setStyle(eq("token"), any(ValueRange.Range.class), captor.capture());
    final var style = captor.getValue();
    final var font = (Map<String, Object>) style.get("font");
    assertThat(font)
        .containsEntry("bold", true)
        .containsEntry("italic", true)
        .containsEntry("fontSize", "10pt");
    assertThat(style)
        .containsEntry("formatter", "yyyy/MM/dd")
        .containsEntry("borderType", "FULL_BORDER");
  }

  @Test
  @DisplayName("getSheetDataFormats documents the formula format")
  void getSheetDataFormats() {
    assertThat(tools.getSheetDataFormats()).contains("formula").contains("=A1");
  }

  @Test
  @DisplayName("lockSheet returns a plain message when no protectId is reported")
  void lockSheetWithoutProtectId() {
    assertThat(tools.lockSheet("token", "sxj5ws", "editing")).isEqualTo("Locked sheet sxj5ws.");
    verify(feishuSheetsService).lockSheet("token", "sxj5ws", "editing");
  }

  @Test
  @DisplayName("lockSheet surfaces the protectId so the model can avoid re-locking")
  void lockSheetWithProtectId() {
    when(feishuSheetsService.lockSheet("token", "sxj5ws", "editing"))
        .thenReturn("7379738014546821122");

    assertThat(tools.lockSheet("token", "sxj5ws", "editing")).contains("7379738014546821122");
  }

  @Test
  @DisplayName("unlockSheet delegates to FeishuSheetsService")
  void unlockSheet() {
    assertThat(tools.unlockSheet("token", "sxj5ws")).isEqualTo("Unlocked sheet sxj5ws.");
    verify(feishuSheetsService).unlockSheet("token", "sxj5ws");
  }

  @Test
  @DisplayName("getProtectedRanges delegates to FeishuSheetsService")
  void getProtectedRanges() {
    final var protectedRange =
        ProtectedRange.builder()
            .protectId("7379738014546821122")
            .sheetId("sxj5ws")
            .lockInfo("editing")
            .build();
    when(feishuSheetsService.getProtectedRanges("token", List.of("7379738014546821122"), "openId"))
        .thenReturn(List.of(protectedRange));

    final var result = tools.getProtectedRanges("token", List.of("7379738014546821122"), "openId");

    assertThat(result).containsExactly(protectedRange);
  }

  @Test
  @DisplayName(
      "getProtectedRanges returns an empty list without calling the service for null/empty input")
  void getProtectedRangesNoIds() {
    assertThat(tools.getProtectedRanges("token", null, null)).isEmpty();
    assertThat(tools.getProtectedRanges("token", List.of(), null)).isEmpty();
    verify(feishuSheetsService, org.mockito.Mockito.never())
        .getProtectedRanges(any(), any(), any());
  }

  @Test
  @DisplayName("getProtectedRanges rejects more than 5 protectIds without calling the service")
  void getProtectedRangesTooManyIds() {
    final var tooMany = List.of("1", "2", "3", "4", "5", "6");

    assertThatThrownBy(() -> tools.getProtectedRanges("token", tooMany, null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(feishuSheetsService, org.mockito.Mockito.never())
        .getProtectedRanges(any(), any(), any());
  }

  @Test
  @DisplayName("createSpreadsheet falls back to the default folder token when none is given")
  void createSpreadsheetUsesDefaultFolder() throws Exception {
    stubSpreadsheetCreation("spreadsheetToken", "sxj5ws");

    tools.createSpreadsheet("My Sheet", null, TOOL_CONTEXT);

    final var captor =
        ArgumentCaptor.forClass(com.lark.oapi.service.sheets.v3.model.CreateSpreadsheetReq.class);
    verify(spreadsheetResource).create(captor.capture());
    assertThat(captor.getValue().getSpreadsheet().getFolderToken()).isEqualTo("ou_userOwnFolder");
  }

  @Test
  @DisplayName("createSpreadsheet grants the requesting user full_access after creation")
  void createSpreadsheetGrantsPermissions() throws Exception {
    stubSpreadsheetCreation("spreadsheetToken", "sxj5ws");

    tools.createSpreadsheet("My Sheet", "folderToken", TOOL_CONTEXT);

    final var captor = ArgumentCaptor.forClass(BatchCreatePermissionMemberReq.class);
    verify(permissionMember).batchCreate(captor.capture());
    final var req = captor.getValue();
    assertThat(req.getToken()).isEqualTo("spreadsheetToken");
    assertThat(req.getType()).isEqualTo("sheet");
    assertThat(req.getBatchCreatePermissionMemberReqBody().getMembers()).hasSize(1);
    final var member = req.getBatchCreatePermissionMemberReqBody().getMembers()[0];
    assertThat(member.getMemberId()).isEqualTo("user1");
    assertThat(member.getPerm()).isEqualTo("full_access");
  }

  @Test
  @DisplayName("createSpreadsheet still returns the created sheet if granting permissions fails")
  void createSpreadsheetSucceedsEvenWhenPermissionGrantFails() throws Exception {
    stubSpreadsheetCreation("spreadsheetToken", "sxj5ws");
    when(permissionMember.batchCreate(any())).thenThrow(new RuntimeException("network error"));

    final var result = tools.createSpreadsheet("My Sheet", "folderToken", TOOL_CONTEXT);

    assertThat(result.spreadsheetToken()).isEqualTo("spreadsheetToken");
    assertThat(result.defaultSheetId()).isEqualTo("sxj5ws");
  }

  private void stubSpreadsheetCreation(final String spreadsheetToken, final String sheetId)
      throws Exception {
    final var spreadsheet =
        com.lark.oapi.service.sheets.v3.model.Spreadsheet.newBuilder()
            .spreadsheetToken(spreadsheetToken)
            .url("https://lv3wgjcyixc.feishu.cn/sheets/" + spreadsheetToken)
            .build();
    final var createRespBody = new CreateSpreadsheetRespBody();
    createRespBody.setSpreadsheet(spreadsheet);
    final var createResp = new CreateSpreadsheetResp();
    createResp.setData(createRespBody);
    when(spreadsheetResource.create(any())).thenReturn(createResp);

    final var sheet =
        com.lark.oapi.service.sheets.v3.model.Sheet.newBuilder().sheetId(sheetId).build();
    final var queryRespBody = new QuerySpreadsheetSheetRespBody();
    queryRespBody.setSheets(new com.lark.oapi.service.sheets.v3.model.Sheet[] {sheet});
    final var queryResp = new QuerySpreadsheetSheetResp();
    queryResp.setData(queryRespBody);
    when(spreadsheetSheetResource.query(any())).thenReturn(queryResp);
  }
}
