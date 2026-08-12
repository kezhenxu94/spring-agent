package me.kezhenxu94.springagent.tools;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.lark.oapi.Client;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.core.utils.UnmarshalRespUtil;
import com.lark.oapi.service.drive.v1.enums.ListFileDirectionEnum;
import com.lark.oapi.service.drive.v1.enums.ListFileOrderByEnum;
import com.lark.oapi.service.drive.v1.enums.ListFileUserIdTypeEnum;
import com.lark.oapi.service.drive.v1.model.DownloadFileReq;
import com.lark.oapi.service.drive.v1.model.ListFileReq;
import com.lark.oapi.service.drive.v1.model.ListFileResp;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuTools {

  final Client feishu;
  final UserWorkspaceFactory userWorkspaceFactory;
  final JsonMapper objectMapper;

  @Value("classpath:/feishu/reply-card.json")
  Resource feishuReplyCard;

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

  @Tool(name = "FeishuSendFile", description = "将本地文件上传至飞书云空间，并发送到指定的飞书会话；如果未指定接收者，默认发送到当前飞书会话。")
  @SneakyThrows
  public String sendFile(
      @ToolParam(description = "要发送的本地文件的绝对路径") String filePath,
      @ToolParam(
              description =
                  "接收者 ID。根据 receiveIdType 不同，可为 open_id / user_id / union_id / email / chat_id 的值；"
                      + "留空则使用当前会话的 chatId",
              required = false)
          String receiveId,
      @ToolParam(
              description =
                  "接收者 ID 类型，可选值: open_id, user_id, union_id, email, chat_id；"
                      + "当 receiveId 留空时此参数将被忽略",
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

    final var file = new File(filePath);
    if (!file.exists()) {
      return "Failed: file not found at " + filePath;
    }

    final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
    final var inWorkspace = userWorkspaceFactory.forOwner(userId).contains(file.toPath());
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

  static String resolveCurrentChatId(ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    return ToolContexts.get(toolContext, ToolContexts.CHAT_ID);
  }

  @SneakyThrows
  @Tool(name = "FeishuSendMessage", description = "向指定的飞书用户或群发送一条消息；内容为 Markdown 文本")
  public String sendMessage(
      @ToolParam(
              description =
                  "接收者 ID。根据 receiveIdType 不同，可为 open_id / user_id / union_id / email / chat_id 的值")
          String receiveId,
      @ToolParam(description = "接收者 ID 类型，可选值: open_id, user_id, union_id, email, chat_id")
          String receiveIdType,
      @ToolParam(description = "Markdown 内容；会被注入到消息模板中") String content) {

    final var cardContent = buildCardContent(content);

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

  String buildCardContent(final String markdown) throws IOException {
    final var card =
        (ObjectNode)
            objectMapper.readTree(feishuReplyCard.getContentAsString(StandardCharsets.UTF_8));
    final var config = card.path("config");
    if (config instanceof ObjectNode configNode) {
      configNode.put("streaming_mode", false);
    }
    final var elements = (ArrayNode) card.path("body").path("elements");
    final var iterator = elements.iterator();
    while (iterator.hasNext()) {
      final var el = iterator.next();
      final var elementId = el.path("element_id").asString();
      if ("stop".equals(elementId) || "usage".equals(elementId)) {
        iterator.remove();
      } else if ("message".equals(elementId)) {
        ((ObjectNode) el).put("content", markdown);
      }
    }
    return objectMapper.writeValueAsString(card);
  }

  @Tool(name = "FeishuDownloadFile", description = "从飞书会话下载文件并保存到产物目录。")
  @SneakyThrows
  public String downloadFeishuFile(
      @ToolParam(description = "包含文件的飞书消息 ID") String messageId,
      @ToolParam(description = "要下载的飞书文件 key") String fileKey,
      @ToolParam(description = "保存时使用的文件名") String fileName,
      ToolContext toolContext) {
    try {
      final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
      final var dest = resolveSafeArtifactPath(fileName, userId);
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
          "读取飞书会话或话题的历史消息, 用于在群聊/话题中被 @ 提及但缺少上下文时获取上下文. "
              + "containerIdType=chat 时 containerId 传 chatId; containerIdType=thread 时传 threadId. "
              + "返回的消息按创建时间倒序排列(最新在前).")
  @SneakyThrows
  // TODO restrict only chat/group members can call this tool, and containerId must be one of the
  // chats the user is in
  public List<MessageHistoryItem> readMessageHistory(
      @ToolParam(description = "容器类型: \"chat\" (单聊或群聊) 或 \"thread\" (话题)")
          final String containerIdType,
      @ToolParam(
              description =
                  "容器 ID: containerIdType=chat 时为 chat_id; containerIdType=thread 时为 thread_id")
          final String containerId,
      @ToolParam(description = "返回条数, 默认 20, 最大 50") final Integer pageSize) {

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
    log.info("Read {} message(s) from {} {}", items.length, containerIdType, containerId);
    return Stream.of(items).map(this::toHistoryItem).toList();
  }

  @Tool(
      name = "FeishuReadMessage",
      description =
          "通过 messageId 读取单条飞书消息的内容, 用于查询用户回复/引用的特定消息. "
              + "如果返回的消息包含 threadId, 说明该消息属于一个话题, "
              + "建议接着调用 FeishuReadMessageHistory 工具, "
              + "传入 containerIdType=thread 和 containerId=threadId, 以获取该话题的上下文.")
  @SneakyThrows
  public MessageHistoryItem readMessage(
      @ToolParam(description = "要读取的飞书消息 ID, 形如 om_xxx") final String messageId) {

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
    log.info("Read message {}", messageId);
    return toHistoryItem(items[0]);
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
          "列出飞书云空间文件夹中的文件，返回文件信息列表 (名称、URL、token、类型、创建/修改时间、所有者)。"
              + "使用场景: 当用户提供飞书文件夹链接并要求查看/整理/导出其中的文件时调用本工具。"
              + "若返回的文件数 > 10, 不要在消息中直接罗列, 而是调用 FeishuCreateSpreadsheet 创建新表格, "
              + "再用 FeishuSheetUpdateRange 将文件列表写入表格, 最后只把表格链接回复给用户。")
  @SneakyThrows
  public List<FeishuFileInfo> listFeishuFolderFiles(
      @ToolParam(description = "飞书文件夹链接") final String folderURL) {

    final var folderToken = extractFolderToken(folderURL);
    if (folderToken == null) {
      log.error("无法从链接中提取文件夹 Token: {}", folderURL);
      return List.of();
    }

    final var files = ImmutableList.<com.lark.oapi.service.drive.v1.model.File>builder();

    ListFileResp folderResponse = null;
    while (folderResponse == null || folderResponse.getData().getHasMore()) {
      for (final var retry : IntStream.range(0, 3).toArray()) {
        folderResponse =
            feishu
                .drive()
                .v1()
                .file()
                .list(
                    ListFileReq.newBuilder()
                        .pageSize(200)
                        .folderToken(folderToken)
                        .orderBy(ListFileOrderByEnum.CREATEDTIME)
                        .direction(ListFileDirectionEnum.ASC)
                        .userIdType(ListFileUserIdTypeEnum.OPEN_ID)
                        .pageToken(
                            folderResponse == null || folderResponse.getData() == null
                                ? null
                                : folderResponse.getData().getNextPageToken())
                        .build());
        if (!folderResponse.success()) {
          log.warn(
              "Failed to list files in folder {}: {}, retry: {}",
              folderToken,
              folderResponse.getCode() + " " + folderResponse.getMsg(),
              retry);
          Thread.sleep(3000);
          continue;
        }
        files.addAll(Stream.of(folderResponse.getData().getFiles()).toList());
        break;
      }
    }

    final var allFiles = files.build();
    final var fileInfoList =
        allFiles.stream().map(file -> buildFileInfo(file)).collect(Collectors.toList());

    log.info("Listed {} files from folder {}", fileInfoList.size(), folderToken);
    return fileInfoList;
  }

  @Tool(
      name = "FeishuDownloadDriveFile",
      description =
          "从飞书云空间下载文件并保存到产物目录。仅支持 type=\"file\" 的二进制文件; "
              + "doc/sheet/slides/bitable/folder 等类型不能直接下载, 需先导出。"
              + "fileToken 可由 FeishuListDriveFolder 工具的返回值中获取。")
  @SneakyThrows
  public String downloadDriveFile(
      @ToolParam(description = "飞书云空间文件 token") String fileToken,
      @ToolParam(description = "保存时使用的文件名") String fileName,
      ToolContext toolContext) {

    if (fileToken == null || fileToken.isBlank()) {
      return "Failed: fileToken is required";
    }
    try {
      final var userId = ToolContexts.require(toolContext, ToolContexts.USER_ID);
      final var dest = resolveSafeArtifactPath(fileName, userId);
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

  String extractFolderToken(final String url) {
    final var pattern = Pattern.compile("/drive/folder/(?<token>[^/?]+)");
    final var matcher = pattern.matcher(url);
    if (matcher.find()) {
      return matcher.group("token");
    }
    return null;
  }

  java.nio.file.Path resolveSafeArtifactPath(final String fileName, final String userId)
      throws IOException {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException("fileName is required");
    }
    final String basename;
    try {
      final var nameOnly = java.nio.file.Path.of(fileName).getFileName();
      basename = nameOnly == null ? null : nameOnly.toString();
    } catch (java.nio.file.InvalidPathException e) {
      throw new IllegalArgumentException("fileName is invalid: " + fileName, e);
    }
    if (basename == null || basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
      throw new IllegalArgumentException("fileName is invalid: " + fileName);
    }
    final var artifacts = userWorkspaceFactory.forOwner(userId).artifacts().normalize();
    final var dest = artifacts.resolve(basename).normalize();
    if (!dest.startsWith(artifacts)) {
      throw new IllegalArgumentException("fileName escapes artifacts directory: " + fileName);
    }
    return dest;
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
