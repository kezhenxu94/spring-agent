package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.core.utils.UnmarshalRespUtil;
import com.lark.oapi.service.drive.v1.model.DownloadFileReq;
import com.lark.oapi.service.im.v1.enums.CreateFileFileTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateFileReq;
import com.lark.oapi.service.im.v1.model.CreateFileReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResp;
import com.lark.oapi.service.im.v1.model.ListMessageResp;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuTools {

  final Client feishu;
  final FeishuChatAccess access;
  final UserWorkspaceFactory userWorkspaceFactory;
  final JsonMapper objectMapper;
  final FeishuMessages messages;
  final FeishuMessageCard messageCard;
  final FeishuDriveService feishuDriveService;
  final FeishuPermissionTools feishuPermissionTools;
  final FeishuProperties feishuProperties;
  final FeishuUserFolders userFolders;

  @Builder
  @Jacksonized
  public static record FeishuFileInfo(
      String name,
      String url,
      String token,
      String type,
      String createdTime,
      String modifiedTime,
      String ownerId) {}

  @Builder
  @Jacksonized
  public static record UploadedDriveFile(String fileToken, String fileName, String url) {}

  @Builder
  @Jacksonized
  public static record MyDriveFolder(
      String folderToken, String folderUrl, String botRootFolderToken) {}

  @Builder
  @Jacksonized
  public static record MessageHistoryItem(
      String messageId,
      String senderId,
      String senderType,
      String msgType,
      String content,
      String threadId,
      String rootId,
      String parentId,
      String createTime,
      List<String> mentions) {}

  @Tool(
      name = "FeishuSendFile",
      description =
          "Upload a local file to Feishu and send it to a conversation. With no recipient it goes"
              + " to the current conversation.")
  @SneakyThrows
  public String sendFile(
      @ToolParam(description = "Absolute path of the local file to send") String filePath,
      @ToolParam(
              description =
                  "Who receives it: an open_id, user_id, union_id, email or chat_id, according to"
                      + " receiveIdType. Leave it out to use the current conversation's chatId",
              required = false)
          String receiveId,
      @ToolParam(
              description =
                  "What kind of id receiveId is: open_id, user_id, union_id, email or chat_id."
                      + " Ignored when receiveId is left out",
              required = false)
          String receiveIdType,
      ToolContext toolContext) {

    final String targetReceiveId;
    final String targetReceiveIdType;
    if (receiveId != null && !receiveId.isBlank()) {
      targetReceiveId = receiveId;
      targetReceiveIdType = receiveIdType;
    } else {
      final var currentChatId = resolveCurrentChatId(toolContext);
      if (Strings.isNullOrEmpty(currentChatId)) {
        return "Failed: no chat target available (provide receiveId or invoke from a chat context)";
      }
      targetReceiveId = currentChatId;
      targetReceiveIdType = "chat_id";
    }

    requireMemberOfTarget(toolContext, targetReceiveId, targetReceiveIdType);

    final var file = new File(filePath);
    if (!file.exists()) {
      return "Failed: file not found at " + filePath;
    }

    final var inWorkspace = userWorkspaceFactory.forRequest(toolContext).contains(file.toPath());
    if (!inWorkspace) {
      log.warn("sendFile rejected out-of-scope path: {}", filePath);
      return "Failed: file must be within an allowed workspace or system temp directory";
    }

    final var uploadResponse =
        feishu
            .im()
            .v1()
            .file()
            .create(
                CreateFileReq.newBuilder()
                    .createFileReqBody(
                        CreateFileReqBody.newBuilder()
                            .fileType(CreateFileFileTypeEnum.STREAM)
                            .fileName(file.getName())
                            .file(file)
                            .build())
                    .build());

    if (uploadResponse.getCode() != 0) {
      log.error("Failed to upload file '{}': {}", filePath, uploadResponse.getMsg());
      return "Failed to upload file: " + uploadResponse.getMsg();
    }

    final var fileKey = uploadResponse.getData().getFileKey();

    final var sendResponse =
        feishu
            .im()
            .v1()
            .message()
            .create(
                CreateMessageReq.newBuilder()
                    .receiveIdType(targetReceiveIdType)
                    .createMessageReqBody(
                        CreateMessageReqBody.newBuilder()
                            .receiveId(targetReceiveId)
                            .msgType("file")
                            .content(String.format("{\"file_key\": \"%s\"}", fileKey))
                            .build())
                    .build());

    file.delete();

    if (sendResponse.getCode() != 0) {
      log.error(
          "Failed to send file to {} ({}): {}",
          targetReceiveId,
          targetReceiveIdType,
          sendResponse.getMsg());
      return "File uploaded but failed to send: " + sendResponse.getMsg();
    }

    log.info("Sent file '{}' to {} ({})", file.getName(), targetReceiveId, targetReceiveIdType);
    return "File sent successfully to " + targetReceiveId + ": " + file.getName();
  }

  /**
   * Refuses to speak into a chat the person this run belongs to is not in.
   *
   * <p>Without it, a chat id is all it takes to have the bot post into any group it was ever
   * invited to, in somebody else's name and as far as that group can tell on their behalf. That is
   * the same hole {@link FeishuChatAccess} closes on the reading side, and the same answer.
   *
   * <p>Only a chat is checked. The other id kinds name a <em>person</em>, and a chat between the
   * bot and one person has no member list to be outside of — the bot messaging somebody directly is
   * the bot's own conversation with them, which is a thing the agent is for rather than a way into
   * anybody's private room.
   */
  private void requireMemberOfTarget(
      final ToolContext toolContext, final String receiveId, final String receiveIdType) {
    if ("chat_id".equalsIgnoreCase(receiveIdType) && !Strings.isNullOrEmpty(receiveId)) {
      access.requireMember(toolContext, receiveId);
    }
  }

  static String resolveCurrentChatId(ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    return ToolContexts.get(toolContext, ToolContexts.CHAT_ID);
  }

  @SneakyThrows
  @Tool(
      name = "FeishuSendMessage",
      description = "Send a markdown message to a Feishu user or group.")
  public String sendMessage(
      @ToolParam(
              description =
                  "Who receives it: an open_id, user_id, union_id, email or chat_id, according to"
                      + " receiveIdType")
          String receiveId,
      @ToolParam(
              description =
                  "What kind of id receiveId is: open_id, user_id, union_id, email or chat_id")
          String receiveIdType,
      @ToolParam(description = "The markdown body, dropped into the message template")
          String content,
      final ToolContext toolContext) {

    requireMemberOfTarget(toolContext, receiveId, receiveIdType);

    final var cardContent = messageCard.render(content);

    final var resp =
        feishu
            .im()
            .v1()
            .message()
            .create(
                CreateMessageReq.newBuilder()
                    .receiveIdType(receiveIdType)
                    .createMessageReqBody(
                        CreateMessageReqBody.newBuilder()
                            .receiveId(receiveId)
                            .msgType("interactive")
                            .content(cardContent)
                            .build())
                    .build());

    if (resp.getCode() != 0) {
      log.error(
          "Failed to send Feishu message to {} ({}): {}", receiveId, receiveIdType, resp.getMsg());
      return "Failed to send message: " + resp.getMsg();
    }
    log.info("Sent Feishu message to {} ({})", receiveId, receiveIdType);
    return "Message sent successfully to " + receiveId + ".";
  }

  @Tool(
      name = "FeishuDownloadFile",
      description = "Download a file from a Feishu conversation into the artifacts directory.")
  @SneakyThrows
  public String downloadFeishuFile(
      @ToolParam(description = "Id of the Feishu message holding the file") String messageId,
      @ToolParam(description = "Key of the Feishu file to download") String fileKey,
      @ToolParam(description = "Filename to save it under") String fileName,
      ToolContext toolContext) {
    try {
      final var dest =
          FeishuFiles.artifactPath(fileName, userWorkspaceFactory.forRequest(toolContext));
      // A message id names no chat, so whose conversation this file is in only becomes knowable by
      // reading the message — and an id and a key are otherwise all it would take to pull a file
      // out of a chat the asker is not in. Checked before a byte is fetched.
      access.requireMember(toolContext, chatOfMessage(messageId));
      log.info("Downloading file: fileKey={}", fileKey);
      final var response =
          feishu
              .im()
              .v1()
              .messageResource()
              .get(
                  GetMessageResourceReq.newBuilder()
                      .messageId(messageId)
                      .fileKey(fileKey)
                      .type("file")
                      .build());
      if (!response.success()) {
        log.warn("Failed to get file: {}, {}, {}", fileKey, response.getCode(), response.getMsg());
        return "Failed to download file: " + response.getCode() + " " + response.getMsg();
      }
      Files.write(dest, response.getData().toByteArray());
      log.info("Saved file to artifacts: {}", dest);
      return dest.toString();
    } catch (IllegalArgumentException | IllegalStateException | IOException e) {
      log.warn("Failed to download file {}: {}", fileKey, e.getMessage());
      return "Failed: " + e.getMessage();
    }
  }

  @Tool(
      name = "FeishuReadMessageHistory",
      description =
          "Read earlier messages from a conversation or a thread, which is how to get the context"
              + " behind an @-mention that does not explain itself. Pass containerId as the chatId"
              + " when containerIdType is chat, and as the threadId when it is thread. Messages"
              + " come back newest first. Only conversations the person you are talking to is in"
              + " can be read.")
  @SneakyThrows
  public List<MessageHistoryItem> readMessageHistory(
      @ToolParam(description = "Either \"chat\" (a direct or group conversation) or \"thread\"")
          final String containerIdType,
      @ToolParam(
              description =
                  "The chat_id when containerIdType is chat, the thread_id when it is thread")
          final String containerId,
      @ToolParam(description = "How many to return; 20 by default, 50 at most")
          final Integer pageSize,
      final ToolContext toolContext) {

    // A chat is named outright, so it is checked before anything is read. A thread is not — a
    // thread_id says nothing about which chat it belongs to — so that one is checked below, off
    // the chat_id the messages themselves carry, and nothing is returned until it passes.
    if ("chat".equalsIgnoreCase(containerIdType)) {
      access.requireMember(toolContext, containerId);
    }

    final var size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 50);
    final var query =
        ListMessageQuery.builder()
            .containerIdType(containerIdType)
            .containerId(containerId)
            .pageSize(size)
            .cardMsgContentType("user_card_content")
            .sortType("ByCreateTimeDesc")
            .build();

    final var raw = feishu.get("/open-apis/im/v1/messages", query, AccessTokenType.Tenant, null);
    final var resp = UnmarshalRespUtil.unmarshalResp(raw, ListMessageResp.class);
    if (resp == null) {
      log.error(
          "Failed to read message history: containerIdType={}, containerId={}, statusCode={}",
          containerIdType,
          containerId,
          raw.getStatusCode());
      throw new IllegalStateException("Failed to read message history: illegal server response");
    }

    if (!resp.success()) {
      log.error(
          "Failed to read message history: containerIdType={}, containerId={}, code={}, msg={}",
          containerIdType,
          containerId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to read message history: " + resp.getMsg());
    }

    final var items = resp.getData().getItems();
    if (items == null) {
      throw new IllegalStateException("Failed to read message history: no items in response");
    }
    // Every chat these messages came from, which for a thread is the one thing that says whose
    // conversation was just read. Checked before the messages are handed over, so a refusal costs
    // a wasted request rather than a leak.
    final var chats =
        Stream.of(items)
            .map(com.lark.oapi.service.im.v1.model.Message::getChatId)
            .filter(chat -> !Strings.isNullOrEmpty(chat))
            .distinct()
            .toList();
    // Messages that name no chat at all cannot be shown to have come from one this person is in,
    // and an unanswerable question about access is a refusal, not a pass.
    if (chats.isEmpty() && items.length > 0) {
      throw new FeishuChatAccess.ChatAccessDeniedException(
          "Refused: which conversation these messages belong to could not be established, and this"
              + " only reads conversations you are in.");
    }
    chats.forEach(chat -> access.requireMember(toolContext, chat));
    log.info("Read {} message(s) from {} {}", items.length, containerIdType, containerId);
    return Stream.of(items).map(this::toHistoryItem).toList();
  }

  @Tool(
      name = "FeishuReadMessage",
      description =
          "Read one Feishu message by id, which is how to see the message a user replied to or"
              + " quoted. If the result carries a threadId the message belongs to a thread, so"
              + " follow up with FeishuReadMessageHistory passing containerIdType=thread and"
              + " containerId=threadId to get the rest of it. Only messages in a conversation the"
              + " person you are talking to is in can be read.")
  @SneakyThrows
  public MessageHistoryItem readMessage(
      @ToolParam(description = "Id of the Feishu message to read, of the form om_xxx")
          final String messageId,
      final ToolContext toolContext) {

    final var message = readOneMessage(messageId);
    // A message id names no chat, so whose conversation this is only becomes knowable here — and
    // an id is all it would otherwise take to read a message out of a chat the asker is not in.
    access.requireMember(toolContext, message.getChatId());
    log.info("Read message {}", messageId);
    return toHistoryItem(message);
  }

  /**
   * The chat one message belongs to, which is the only thing that says whose conversation it is.
   */
  @SneakyThrows
  String chatOfMessage(final String messageId) {
    return readOneMessage(messageId).getChatId();
  }

  @SneakyThrows
  private com.lark.oapi.service.im.v1.model.Message readOneMessage(final String messageId) {
    final var query = GetMessageQuery.builder().cardMsgContentType("user_card_content").build();

    final var raw =
        feishu.get("/open-apis/im/v1/messages/" + messageId, query, AccessTokenType.Tenant, null);
    final var resp = UnmarshalRespUtil.unmarshalResp(raw, GetMessageResp.class);
    if (resp == null) {
      log.error(
          "Failed to read message: messageId={}, statusCode={}", messageId, raw.getStatusCode());
      throw new IllegalStateException("Failed to read message: illegal server response");
    }

    if (!resp.success()) {
      log.error(
          "Failed to read message: messageId={}, code={}, msg={}",
          messageId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to read message: " + resp.getMsg());
    }

    final var items = resp.getData().getItems();
    if (items == null || items.length == 0) {
      throw new IllegalStateException("Failed to read message: no message returned");
    }
    return items[0];
  }

  MessageHistoryItem toHistoryItem(final com.lark.oapi.service.im.v1.model.Message msg) {
    final var mentions =
        msg.getMentions() == null
            ? List.<String>of()
            : Stream.of(msg.getMentions()).map(m -> m.getName() + " (" + m.getId() + ")").toList();
    final var sender = msg.getSender();
    return MessageHistoryItem.builder()
        .messageId(msg.getMessageId())
        .senderId(sender == null ? null : sender.getId())
        .senderType(sender == null ? null : sender.getSenderType())
        .msgType(msg.getMsgType())
        .content(msg.getBody() == null ? null : msg.getBody().getContent())
        .threadId(msg.getThreadId())
        .rootId(msg.getRootId())
        .parentId(msg.getParentId())
        .createTime(msg.getCreateTime())
        .mentions(mentions)
        .build();
  }

  @Tool(
      name = "FeishuListDriveFolder",
      description =
          "List the files in a Feishu drive folder: name, URL, token, type, creation and"
              + " modification times, and owner. Use it when a user gives a folder link and wants"
              + " what is in it seen, tidied or exported.\n"
              + "Past ten files, do not list them in the reply: create a spreadsheet with"
              + " FeishuCreateSpreadsheet, write the list into it with FeishuSheetUpdateRange, and"
              + " reply with nothing but the link to it.")
  @SneakyThrows
  public List<FeishuFileInfo> listFeishuFolderFiles(
      @ToolParam(description = "Link to the Feishu folder") final String folderURL) {

    final var folderToken = extractFolderToken(folderURL);
    if (folderToken == null) {
      log.error("Could not find a folder token in the link: {}", folderURL);
      return List.of();
    }

    final var fileInfoList =
        feishuDriveService.listFolderFiles(folderToken).stream()
            .map(this::buildFileInfo)
            .collect(Collectors.toList());

    log.info("Listed {} files from folder {}", fileInfoList.size(), folderToken);
    return fileInfoList;
  }

  @Tool(
      name = "FeishuDownloadDriveFile",
      description =
          "Download a file from Feishu drive into the artifacts directory. Only binary files, the"
              + " ones of type \"file\": docs, sheets, slides, bitables and folders have to be"
              + " exported first. FeishuListDriveFolder returns the fileToken to pass here.")
  @SneakyThrows
  public String downloadDriveFile(
      @ToolParam(description = "Token of the file in Feishu drive") String fileToken,
      @ToolParam(description = "Filename to save it under") String fileName,
      ToolContext toolContext) {

    if (fileToken == null || fileToken.isBlank()) {
      return "Failed: fileToken is required";
    }
    try {
      final var dest =
          FeishuFiles.artifactPath(fileName, userWorkspaceFactory.forRequest(toolContext));
      log.info("Downloading Drive file: token={}", fileToken);
      final var response =
          feishu
              .drive()
              .v1()
              .file()
              .download(DownloadFileReq.newBuilder().fileToken(fileToken).build());
      if (!response.success()) {
        log.warn(
            "Failed to download Drive file: token={}, code={}, msg={}",
            fileToken,
            response.getCode(),
            response.getMsg());
        return "Failed to download Drive file: " + response.getCode() + " " + response.getMsg();
      }
      Files.write(dest, response.getData().toByteArray());
      log.info("Saved Drive file to artifacts: {}", dest);
      return dest.toString();
    } catch (IllegalArgumentException | IllegalStateException | IOException e) {
      log.warn("Failed to download Drive file {}: {}", fileToken, e.getMessage());
      return "Failed: " + e.getMessage();
    }
  }

  @Tool(
      name = "FeishuUploadDriveFile",
      description =
          "Put a local file into a Feishu drive folder as a file, and answer with its link — which"
              + " is what to give the person who asked. Use it when they want a file kept in the"
              + " drive, or shared as a link rather than as a chat attachment; FeishuSendFile is"
              + " the one that sends it into the conversation instead.\n"
              + "The file is stored as it is, not converted: a spreadsheet uploaded this way is a"
              + " downloadable .xlsx and not a Feishu spreadsheet anybody can open and edit. Call"
              + " FeishuImportFile for that. A large file is uploaded in chunks and needs nothing"
              + " extra asked of it; an empty one is refused.")
  @SneakyThrows
  public UploadedDriveFile uploadDriveFile(
      @ToolParam(description = "Absolute path of the local file to upload") final String filePath,
      @ToolParam(
              description =
                  "Token of the drive folder to put it in, as FeishuListDriveFolder's link"
                      + " carries; the folder belonging to whoever you are talking to is used"
                      + " when left out",
              required = false)
          final String folderToken,
      @ToolParam(
              description = "Name to store it under; the local file's name is used when left out",
              required = false)
          final String fileName,
      final ToolContext toolContext) {

    if (filePath == null || filePath.isBlank()) {
      throw new IllegalArgumentException("filePath is required");
    }
    final var file = new File(filePath);
    if (!file.isFile()) {
      throw new IllegalArgumentException("No file at " + filePath);
    }
    // The same rule every tool here that reads a local path applies: what may leave this machine is
    // what this request's own scopes hold, so that a path is never a way to publish someone else's
    // files — or the host's — into a drive.
    if (!userWorkspaceFactory.forRequest(toolContext).contains(file.toPath())) {
      log.warn("uploadDriveFile rejected out-of-scope path: {}", filePath);
      throw new IllegalArgumentException("The file must be within an allowed workspace");
    }

    final var targetFolderToken =
        Strings.isNullOrEmpty(folderToken) ? userFolders.folderFor(toolContext) : folderToken;
    final var name = Strings.isNullOrEmpty(fileName) ? file.getName() : fileName;
    final var fileToken = feishuDriveService.uploadFile(name, targetFolderToken, file);
    // Without this the upload is visible to the bot alone, which makes the link useless to the very
    // person it is about to be given to.
    feishuPermissionTools.grantDefaultPermissions(toolContext, fileToken, "file");

    return UploadedDriveFile.builder()
        .fileToken(fileToken)
        .fileName(name)
        .url("https://" + feishuProperties.tenantDomain() + "/file/" + fileToken)
        .build();
  }

  @Tool(
      name = "FeishuMyDriveFolder",
      description =
          "Where this person's Feishu files go: the token and link of the drive folder that belongs"
              + " to whoever you are talking to, and the token of the bot's own space that folder"
              + " sits in. The folder is made the first time it is needed and its ownership is"
              + " handed to them, so it is theirs rather than the bot's.\n"
              + "Use it to answer \"where did you put that\" or \"where do my documents live\","
              + " and to give the person a link to everything the agent has made for them. Nothing"
              + " needs it before creating a document, a spreadsheet or a base: leaving folderToken"
              + " out of those tools already puts the result here.")
  public MyDriveFolder myDriveFolder(final ToolContext toolContext) {
    final var folderToken = userFolders.folderFor(toolContext);
    return MyDriveFolder.builder()
        .folderToken(folderToken)
        .folderUrl("https://" + feishuProperties.tenantDomain() + "/drive/folder/" + folderToken)
        .botRootFolderToken(feishuDriveService.rootFolderToken())
        .build();
  }

  /**
   * The folder a link names, or the token itself when that is what was given.
   *
   * <p>Through {@link FeishuGuardedTools#resolve}, which is what {@link FeishuAccessInterceptor}
   * checked this call against: resolving it a second way here could list a folder other than the
   * one that was allowed.
   */
  String extractFolderToken(final String url) {
    final var token = FeishuGuardedTools.resolve(url, FeishuGuardedTools.FOLDER).token();
    return Strings.isNullOrEmpty(token) ? null : token;
  }

  FeishuFileInfo buildFileInfo(final com.lark.oapi.service.drive.v1.model.File file) {
    return FeishuFileInfo.builder()
        .name(file.getName())
        .url(file.getUrl())
        .token(file.getToken())
        .type(file.getType())
        .createdTime(file.getCreatedTime())
        .modifiedTime(file.getModifiedTime())
        .ownerId(file.getOwnerId())
        .build();
  }
}
