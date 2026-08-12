package me.kezhenxu94.springagent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.V1;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberResp;
import com.lark.oapi.service.drive.v1.resource.PermissionMember;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class FeishuPermissionToolsTest {

  @Mock private Client feishu;
  @Mock private DriveService driveService;
  @Mock private V1 driveV1;
  @Mock private PermissionMember permissionMember;

  private FeishuPermissionTools tools;

  @BeforeEach
  void setUp() throws Exception {
    when(feishu.drive()).thenReturn(driveService);
    when(driveService.v1()).thenReturn(driveV1);
    when(driveV1.permissionMember()).thenReturn(permissionMember);
    tools = new FeishuPermissionTools(feishu);
  }

  private static ToolContext toolContext(String userId, String chatId, String chatType) {
    return new ToolContext(
        Map.of(
            ToolContexts.KEY_USER_ID,
            userId,
            ToolContexts.KEY_CHAT_ID,
            chatId,
            ToolContexts.KEY_CHAT_TYPE,
            chatType));
  }

  @Test
  @DisplayName("p2p chat only grants full_access to the requesting user")
  void p2pGrantsOnlyUserFullAccess() throws Exception {
    when(permissionMember.batchCreate(any())).thenReturn(new BatchCreatePermissionMemberResp());

    tools.grantDefaultPermissions(toolContext("user1", "chat1", "p2p"), "token", "docx");

    final var captor = ArgumentCaptor.forClass(BatchCreatePermissionMemberReq.class);
    verify(permissionMember).batchCreate(captor.capture());
    final var members = captor.getValue().getBatchCreatePermissionMemberReqBody().getMembers();
    assertThat(members).hasSize(1);
    assertThat(members[0].getMemberId()).isEqualTo("user1");
    assertThat(members[0].getPerm()).isEqualTo("full_access");
    assertThat(members[0].getType()).isEqualTo("user");
  }

  @Test
  @DisplayName("group chat grants view to the chat in addition to full_access for the user")
  void groupChatGrantsViewToChatAndFullAccessToUser() throws Exception {
    when(permissionMember.batchCreate(any())).thenReturn(new BatchCreatePermissionMemberResp());

    tools.grantDefaultPermissions(toolContext("user1", "chat1", "group"), "token", "sheet");

    final var captor = ArgumentCaptor.forClass(BatchCreatePermissionMemberReq.class);
    verify(permissionMember).batchCreate(captor.capture());
    final var members = captor.getValue().getBatchCreatePermissionMemberReqBody().getMembers();
    assertThat(members).hasSize(2);
    assertThat(members[0].getMemberId()).isEqualTo("chat1");
    assertThat(members[0].getPerm()).isEqualTo("view");
    assertThat(members[0].getType()).isEqualTo("chat");
    assertThat(members[1].getMemberId()).isEqualTo("user1");
    assertThat(members[1].getPerm()).isEqualTo("full_access");
  }

  @Test
  @DisplayName("does not throw when the Feishu API call fails")
  void doesNotThrowWhenBatchCreateThrows() throws Exception {
    when(permissionMember.batchCreate(any())).thenThrow(new RuntimeException("network error"));

    tools.grantDefaultPermissions(toolContext("user1", "chat1", "p2p"), "token", "docx");
  }

  @Test
  @DisplayName("does not throw when the Feishu API reports failure")
  void doesNotThrowWhenBatchCreateFails() throws Exception {
    final var resp = new BatchCreatePermissionMemberResp();
    resp.setCode(1063002);
    resp.setMsg("Permission denied");
    when(permissionMember.batchCreate(any())).thenReturn(resp);

    tools.grantDefaultPermissions(toolContext("user1", "chat1", "p2p"), "token", "docx");
  }
}
