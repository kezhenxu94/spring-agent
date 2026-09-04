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
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class FeishuPermissionTools {

  final Client feishu;
  final FeishuDriveService feishuDriveService;

  /**
   * Hands a newly created node to the person the run belongs to: they become a collaborator, then
   * its owner, and in a group chat the chat itself is left able to view it.
   *
   * <p>Ownership rather than {@code full_access} alone, because the two differ in the places that
   * only show up later — the node counts against whoever owns it, and a node still owned by the bot
   * is a node with no owner left once the application is uninstalled or its tenant token revoked.
   * The bot stays a {@code full_access} collaborator either way (see {@link
   * FeishuDriveService#transferOwner}), so every tool that edits the node afterwards is unaffected.
   *
   * <p>The grant has to land before the handover: Feishu refuses to transfer ownership to somebody
   * who is not a collaborator yet.
   *
   * <p>Best-effort, unlike {@link #grant}: the node exists by the time this runs, so failing the
   * tool call would report a document that was in fact created as an error and have the model make
   * a second one. A node left owned by the bot is still readable and writable by the person, which
   * is the difference between this and the folder handover in {@link FeishuUserFolders}, where a
   * silent failure would leave a folder nobody but the bot can see.
   */
  void handOverToAsker(final ToolContext toolContext, final String token, final String docType) {
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
      feishuDriveService.transferOwner(token, docType, userId);
    } catch (Exception e) {
      log.error("Failed to hand {} {} over to whoever asked for it", docType, token, e);
    }
  }

  /**
   * Adds collaborators to one node, and says so when it does not work.
   *
   * <p>This raises rather than logging, because both of its callers hand ownership over next and
   * Feishu refuses to transfer a node to somebody who is not a collaborator on it: a grant that
   * silently did nothing would turn into a second, more confusing failure. What each caller does
   * with the exception differs — {@link FeishuUserFolders} lets it out, since a folder nobody but
   * the bot can see is worse than no folder, while {@link #handOverToAsker} logs it and leaves the
   * node as it was created.
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
