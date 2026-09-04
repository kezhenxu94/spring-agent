package me.kezhenxu94.springagent.integration.feishu.tools;

import com.lark.oapi.Client;
import com.lark.oapi.service.drive.v1.model.BaseMember;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberReqBody;
import com.lark.oapi.service.drive.v1.model.BatchCreatePermissionMemberResp;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class FeishuPermissionTools {

  final Client feishu;

  /**
   * Grants the requesting user full_access, and (for group chats) grants the chat itself view
   * access, so a newly created doc/sheet isn't left visible only to the bot. Best-effort: the
   * doc/sheet has already been created by the time this runs, so any failure here is logged rather
   * than propagated, to avoid failing an otherwise-successful creation.
   */
  void grantDefaultPermissions(
      final ToolContext toolContext, final String token, final String docType) {
    try {
      final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
      final var chatId = ToolContexts.get(toolContext, ToolContexts.CHAT_ID);
      final var chatType = ToolContexts.get(toolContext, ToolContexts.CHAT_TYPE);

      final var members = new ArrayList<BaseMember>();
      if ("group".equalsIgnoreCase(chatType) && chatId != null && !chatId.isBlank()) {
        members.add(
            BaseMember.newBuilder()
                .memberType("openchat")
                .memberId(chatId)
                .perm("view")
                .type("chat")
                .build());
      }
      members.add(
          BaseMember.newBuilder()
              .memberType("openid")
              .memberId(userId)
              .perm("full_access")
              .type("user")
              .build());

      grant(token, docType, members.toArray(new BaseMember[0]));
    } catch (Exception e) {
      log.error("Failed to grant default permissions on {} {}", docType, token, e);
    }
  }

  /**
   * Adds collaborators to one node, and says so when it does not work.
   *
   * <p>Unlike {@link #grantDefaultPermissions} this raises: its callers are the ones for which a
   * grant that silently did nothing leaves something worse than an over-private document — a folder
   * whose ownership is about to be handed to somebody who is not on it yet.
   */
  void grant(final String token, final String docType, final BaseMember... members) {
    final BatchCreatePermissionMemberResp resp;
    try {
      resp =
          feishu
              .drive()
              .v1()
              .permissionMember()
              .batchCreate(
                  BatchCreatePermissionMemberReq.newBuilder()
                      .token(token)
                      .type(docType)
                      .batchCreatePermissionMemberReqBody(
                          BatchCreatePermissionMemberReqBody.newBuilder().members(members).build())
                      .build());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to grant permissions on " + docType + " " + token + ": " + e.getMessage(), e);
    }
    if (resp == null || !resp.success()) {
      log.error(
          "Failed to grant permissions on {} {}: {}, {}",
          docType,
          token,
          resp == null ? null : resp.getCode(),
          resp == null ? null : resp.getMsg());
      throw new IllegalStateException(
          "Failed to grant permissions on "
              + docType
              + " "
              + token
              + ": "
              + (resp == null ? "no response" : resp.getCode() + " " + resp.getMsg()));
    }
    log.info("Granted permissions on {} {} to {}", docType, token, List.of(members));
  }
}
