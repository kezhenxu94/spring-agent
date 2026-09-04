package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.docx.v1.model.Document;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.V1;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberResp;
import com.lark.oapi.service.drive.v1.resource.PermissionMember;
import java.util.Map;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.docx.FeishuDocumentBodyWriter;
import me.kezhenxu94.springagent.integration.feishu.docx.FeishuDocxService;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class FeishuDocToolsTest {

  @Mock private FeishuDocxService feishuDocxService;
  @Mock private Client feishu;
  @Mock private DriveService driveService;
  @Mock private V1 driveV1;
  @Mock private PermissionMember permissionMember;
  @Mock private FeishuUserFolders userFolders;

  private FeishuDocTools tools;

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
    tools =
        new FeishuDocTools(
            feishuDocxService,
            new FeishuDocumentBodyWriter(
                feishuDocxService, new FeishuDriveService(feishu, new JsonMapper())),
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location("build/tmp/doc-tools-test").build()),
            new FeishuDriveService(feishu, new JsonMapper()),
            new JsonMapper(),
            new FeishuProperties(
                null,
                null,
                "lv3wgjcyixc.feishu.cn",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            new FeishuPermissionTools(feishu),
            userFolders,
            new FeishuGuides(null));
  }

  @Test
  @DisplayName("createDocument builds a doc link from the document id since the API returns none")
  void createDocument() {
    final var document =
        Document.newBuilder()
            .documentId("doxcngNygNfuqhxTBf588jabcef")
            .revisionId(1)
            .title("My Doc")
            .build();
    when(feishuDocxService.createDocument("folderToken", "My Doc")).thenReturn(document);

    final var result = tools.createDocument("My Doc", "folderToken", TOOL_CONTEXT);

    assertThat(result.documentId()).isEqualTo("doxcngNygNfuqhxTBf588jabcef");
    assertThat(result.revisionId()).isEqualTo(1);
    assertThat(result.title()).isEqualTo("My Doc");
    assertThat(result.url())
        .isEqualTo("https://lv3wgjcyixc.feishu.cn/docx/doxcngNygNfuqhxTBf588jabcef");
  }

  @Test
  @DisplayName("createDocument falls back to the folder belonging to the requester")
  void createDocumentUsesTheRequestersOwnFolder() {
    final var document =
        Document.newBuilder().documentId("doxDefaultFolder").revisionId(1).title("My Doc").build();
    when(feishuDocxService.createDocument("ou_userOwnFolder", "My Doc")).thenReturn(document);

    tools.createDocument("My Doc", null, TOOL_CONTEXT);

    verify(feishuDocxService).createDocument("ou_userOwnFolder", "My Doc");
  }

  @Test
  @DisplayName("createDocument grants the requesting user full_access after creation")
  void createDocumentGrantsPermissions() throws Exception {
    final var document =
        Document.newBuilder().documentId("doxcngNygNfuqhxTBf588jabcef").revisionId(1).build();
    when(feishuDocxService.createDocument("folderToken", "My Doc")).thenReturn(document);

    tools.createDocument("My Doc", "folderToken", TOOL_CONTEXT);

    final var captor = ArgumentCaptor.forClass(BatchCreatePermissionMemberReq.class);
    verify(permissionMember).batchCreate(captor.capture());
    final var req = captor.getValue();
    assertThat(req.getToken()).isEqualTo("doxcngNygNfuqhxTBf588jabcef");
    assertThat(req.getType()).isEqualTo("docx");
    assertThat(req.getBatchCreatePermissionMemberReqBody().getMembers()).hasSize(1);
    final var member = req.getBatchCreatePermissionMemberReqBody().getMembers()[0];
    assertThat(member.getMemberId()).isEqualTo("user1");
    assertThat(member.getPerm()).isEqualTo("full_access");
  }

  @Test
  @DisplayName("createDocument still returns the created doc if granting permissions fails")
  void createDocumentSucceedsEvenWhenPermissionGrantFails() throws Exception {
    final var document =
        Document.newBuilder().documentId("doxcngNygNfuqhxTBf588jabcef").revisionId(1).build();
    when(feishuDocxService.createDocument("folderToken", "My Doc")).thenReturn(document);
    when(permissionMember.batchCreate(any())).thenThrow(new RuntimeException("network error"));

    final var result = tools.createDocument("My Doc", "folderToken", TOOL_CONTEXT);

    assertThat(result.documentId()).isEqualTo("doxcngNygNfuqhxTBf588jabcef");
  }
}
