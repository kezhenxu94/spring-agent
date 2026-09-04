package me.kezhenxu94.springagent.integration.feishu.drive;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.service.drive.v1.enums.ListFileDirectionEnum;
import com.lark.oapi.service.drive.v1.enums.ListFileOrderByEnum;
import com.lark.oapi.service.drive.v1.enums.ListFileUserIdTypeEnum;
import com.lark.oapi.service.drive.v1.model.CreateExportTaskReq;
import com.lark.oapi.service.drive.v1.model.CreateFolderFileReq;
import com.lark.oapi.service.drive.v1.model.CreateFolderFileReqBody;
import com.lark.oapi.service.drive.v1.model.CreateImportTaskReq;
import com.lark.oapi.service.drive.v1.model.DownloadExportTaskReq;
import com.lark.oapi.service.drive.v1.model.ExportTask;
import com.lark.oapi.service.drive.v1.model.FileUploadInfo;
import com.lark.oapi.service.drive.v1.model.GetExportTaskReq;
import com.lark.oapi.service.drive.v1.model.GetImportTaskReq;
import com.lark.oapi.service.drive.v1.model.ImportTask;
import com.lark.oapi.service.drive.v1.model.ImportTaskMountPoint;
import com.lark.oapi.service.drive.v1.model.ListFileReq;
import com.lark.oapi.service.drive.v1.model.ListFileResp;
import com.lark.oapi.service.drive.v1.model.ListPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.Member;
import com.lark.oapi.service.drive.v1.model.Owner;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.UploadAllFileReq;
import com.lark.oapi.service.drive.v1.model.UploadAllFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReq;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReqBody;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileReq;
import com.lark.oapi.service.drive.v1.model.UploadFinishFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadPartFileReq;
import com.lark.oapi.service.drive.v1.model.UploadPartFileReqBody;
import com.lark.oapi.service.drive.v1.model.UploadPrepareFileReq;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.Adler32;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drive calls that belong to no one document type.
 *
 * <p>Uploading a medium is the same endpoint whatever will hold the result — a document's image
 * block, a bitable's attachment cell — and only {@code parentType} and {@code parentNode} say
 * which. It lives here rather than in one of those services so that neither has to reach into the
 * other.
 *
 * <p>Import and export live here for the same reason: one file becomes a document, a spreadsheet or
 * a base depending on nothing but a string, and one document of any of those types becomes a file.
 * Neither is a call any single document service could own.
 *
 * <p>Putting a file into the drive as a file — rather than as a medium some document refers to, or
 * as the source of an import — is a third thing again, and the endpoint is a different one: {@code
 * files/upload_all}, whose product is a node in a folder rather than a token only its parent
 * understands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuDriveService {

  /**
   * The parent type an upload has to name for the result to be importable. Anything else produces a
   * token the import endpoint rejects, and unlike the other parent types this one takes no parent
   * node: the file is not going into a document, it is going to become one.
   */
  private static final String IMPORT_PARENT_TYPE = "ccm_import_open";

  /** Mount type 1, the only one there is: the imported document lands in the drive. */
  private static final int MOUNT_TO_DRIVE = 1;

  /**
   * The only upload point a file can be given, as opposed to a medium: the drive itself. The
   * companion {@code parentNode} is then a folder token, and a file is a node in that folder rather
   * than something a document holds.
   */
  private static final String DRIVE_PARENT_TYPE = "explorer";

  /**
   * Past this, {@code files/upload_all} refuses the file (1061043) and the only way in is the
   * three-call chunked flow. Feishu's own number, not a choice made here.
   */
  private static final long SINGLE_UPLOAD_LIMIT = 20L * 1024 * 1024;

  /**
   * Where uploading stops being possible at all. The endpoints declare every size as a 32-bit int,
   * so a larger file cannot even be described to them — and truncating silently would upload a
   * prefix and call it the file. Feishu's per-edition limits are all far below this; this is only
   * the point past which the API itself has no way to say what is being sent.
   */
  private static final long MAX_UPLOAD_SIZE = Integer.MAX_VALUE;

  /**
   * The two statuses that mean "come back later"; 0 means done and everything else is a failure
   * whose number is worth reporting, since Feishu documents what each one means and its {@code
   * job_error_msg} often does not.
   */
  private static final int JOB_SUCCEEDED = 0;

  private static final int JOB_INITIALISING = 1;

  private static final int JOB_PROCESSING = 2;

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  /**
   * How long a task may run before waiting on it is given up.
   *
   * <p>A bound rather than patience because the wait blocks the tool call that started it: a model
   * left hanging on a large import has no way to say anything to the person waiting on it, so a
   * clear failure naming the ticket beats an answer that may never come. Three minutes covers every
   * import and export of a document a chat is plausibly about; the giant ones the API also accepts
   * are not work a chat turn should be holding open.
   */
  private static final Duration MAX_WAIT = Duration.ofMinutes(3);

  /**
   * How many pages of one folder's contents a listing will read. At the page size below that is
   * 20,000 entries — past any folder this application has business walking, and the bound is here
   * so that one folder token cannot turn a listing into an unbounded number of requests.
   */
  private static final int MAX_FOLDER_PAGES = 100;

  private static final int FOLDER_PAGE_SIZE = 200;

  /** How many times a failing page of a folder listing is asked for again before giving up. */
  private static final int LIST_RETRIES = 3;

  private static final Duration LIST_RETRY_DELAY = Duration.ofSeconds(3);

  private final Client feishu;

  private final JsonMapper objectMapper;

  /**
   * The bot's own space, resolved once.
   *
   * <p>Held for the life of the process rather than asked for each time: the root of a drive space
   * is minted with the space and never changes, and every folder this application resolves hangs
   * off it. Not {@code final} only because resolving it is a network call that must not happen
   * during construction.
   */
  private volatile String rootFolderToken;

  /**
   * The token of the bot's own "my space" root folder — the folder every per-person folder is
   * created in.
   *
   * <p>Through the raw endpoint because the SDK has no binding for it, the way {@code
   * FeishuBotTools} and {@code FeishuSheetsService} reach the endpoints it also lacks. The bot is
   * what the tenant token authorises, so what comes back is the application's own space and not
   * anybody else's.
   */
  @SneakyThrows
  public String rootFolderToken() {
    final var cached = rootFolderToken;
    if (cached != null) {
      return cached;
    }
    final var raw =
        feishu.get("/open-apis/drive/explorer/v2/root_folder/meta", null, AccessTokenType.Tenant);
    final var body = objectMapper.readTree(new String(raw.getBody(), StandardCharsets.UTF_8));
    final var code = body.path("code").asInt(-1);
    if (code != 0) {
      log.error(
          "Failed to read the bot's root folder: code={}, msg={}",
          code,
          body.path("msg").asString(""));
      throw new IllegalStateException(
          "Failed to read the bot's root folder: " + code + " " + body.path("msg").asString(""));
    }
    final var token = body.path("data").path("token").asString("");
    if (token.isBlank()) {
      throw new IllegalStateException("The bot's root folder came back without a token");
    }
    log.info("The bot's root folder is {}", token);
    rootFolderToken = token;
    return token;
  }

  /** Creates a folder under {@code parentFolderToken} and returns its token. */
  @SneakyThrows
  public String createFolder(final String parentFolderToken, final String name) {
    final var resp =
        feishu
            .drive()
            .v1()
            .file()
            .createFolder(
                CreateFolderFileReq.newBuilder()
                    .createFolderFileReqBody(
                        CreateFolderFileReqBody.newBuilder()
                            .name(name)
                            .folderToken(parentFolderToken)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create folder '{}' under {}: {}, {}",
          name,
          parentFolderToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create folder: " + resp.getMsg());
    }
    final var token = resp.getData().getToken();
    log.info("Created folder '{}' under {}: {}", name, parentFolderToken, token);
    return token;
  }

  /**
   * Everything in one folder, every page of it.
   *
   * <p>Here rather than in the tool that lists a folder for a person, because resolving somebody's
   * own folder walks the same listing and neither should be the other's caller.
   */
  @SneakyThrows
  public List<com.lark.oapi.service.drive.v1.model.File> listFolderFiles(final String folderToken) {
    final var files = new ArrayList<com.lark.oapi.service.drive.v1.model.File>();
    String pageToken = null;
    for (var page = 0; page < MAX_FOLDER_PAGES; page++) {
      final var resp = listFolderPage(folderToken, pageToken);
      final var data = resp.getData();
      if (data.getFiles() != null) {
        files.addAll(List.of(data.getFiles()));
      }
      if (!Boolean.TRUE.equals(data.getHasMore())
          || Strings.isNullOrEmpty(data.getNextPageToken())) {
        return files;
      }
      pageToken = data.getNextPageToken();
    }
    // Not silently truncated: a caller deciding whether somebody's folder exists would read a
    // partial listing as "it does not" and create a second one.
    throw new IllegalStateException(
        "Folder "
            + folderToken
            + " has more than "
            + MAX_FOLDER_PAGES * FOLDER_PAGE_SIZE
            + " entries");
  }

  @SneakyThrows
  private ListFileResp listFolderPage(final String folderToken, final String pageToken) {
    ListFileResp last = null;
    for (var retry = 0; retry < LIST_RETRIES; retry++) {
      last =
          feishu
              .drive()
              .v1()
              .file()
              .list(
                  ListFileReq.newBuilder()
                      .pageSize(FOLDER_PAGE_SIZE)
                      .folderToken(folderToken)
                      .orderBy(ListFileOrderByEnum.CREATEDTIME)
                      .direction(ListFileDirectionEnum.ASC)
                      .userIdType(ListFileUserIdTypeEnum.OPEN_ID)
                      .pageToken(pageToken)
                      .build());
      if (last.success()) {
        return last;
      }
      log.warn(
          "Failed to list files in folder {}: {} {}, retry {}",
          folderToken,
          last.getCode(),
          last.getMsg(),
          retry);
      Thread.sleep(LIST_RETRY_DELAY.toMillis());
    }
    throw new IllegalStateException(
        "Failed to list folder "
            + folderToken
            + ": "
            + (last == null ? "no response" : last.getMsg()));
  }

  /**
   * The collaborators of one document, spreadsheet, base, file or folder.
   *
   * <p>{@code type} is a plain string rather than the SDK's enum on purpose: that enum has no
   * {@code folder}, the endpoint does, and a folder's collaborator list is the only way to tell
   * whether somebody may go into one.
   */
  @SneakyThrows
  public List<Member> listCollaborators(final String token, final String type) {
    final var resp =
        feishu
            .drive()
            .v1()
            .permissionMember()
            .list(ListPermissionMemberReq.newBuilder().token(token).type(type).build());
    if (!resp.success()) {
      log.warn(
          "Failed to list the collaborators of {} {}: {}, {}",
          type,
          token,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to list collaborators: " + resp.getMsg());
    }
    final var items = resp.getData() == null ? null : resp.getData().getItems();
    return items == null ? List.of() : List.of(items);
  }

  /**
   * Hands ownership of a node to a person, without moving it and without the bot losing its own way
   * in.
   *
   * <p>Three of the four flags are load-bearing. {@code stayPut} keeps the node where it is: the
   * per-person folders are found again by listing the bot's own space, so one that moved into the
   * new owner's space would be invisible on the next run and made a second time. {@code
   * oldOwnerPerm} is what leaves the bot a {@code full_access} collaborator on a folder it no
   * longer owns — without it the very next document the agent creates for that person fails. {@code
   * needNotification} off because the transfer is bookkeeping the person did not ask for and a
   * notification about it explains nothing.
   */
  @SneakyThrows
  public void transferOwner(final String token, final String type, final String openId) {
    final var resp =
        feishu
            .drive()
            .v1()
            .permissionMember()
            .transferOwner(
                TransferOwnerPermissionMemberReq.newBuilder()
                    .token(token)
                    .type(type)
                    .stayPut(true)
                    .removeOldOwner(false)
                    .oldOwnerPerm("full_access")
                    .needNotification(false)
                    .owner(Owner.newBuilder().memberType("openid").memberId(openId).build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to transfer ownership of {} {} to {}: {}, {}",
          type,
          token,
          openId,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to transfer ownership: " + resp.getMsg());
    }
    log.info("Transferred ownership of {} {} to {}", type, token, openId);
  }

  /**
   * Uploads a file and returns the {@code file_token} that whatever will hold it refers to it by.
   *
   * <p>The token is bound to {@code parentNode}: a token uploaded against one document or base is
   * rejected by any other, so the upload has to name the node the medium is destined for rather
   * than being done once and reused.
   */
  public String uploadMedia(
      final String fileName, final String parentType, final String parentNode, final File file) {
    return uploadMedia(fileName, parentType, parentNode, file, null);
  }

  /**
   * Uploads a local file as the source of an import, returning the {@code file_token} the import
   * task will name.
   *
   * <p>The {@code extra} the upload carries is what decides the import, not the import task that
   * follows it: declare a different {@code obj_type} or {@code file_extension} there than in the
   * task and Feishu answers 1069911 or 1069910 rather than reconciling the two. Both are therefore
   * taken from the same arguments the caller goes on to create the task with.
   *
   * <p>The uploaded file is temporary — Feishu deletes it once the import is done, and expires the
   * token after five minutes — which is why nothing here hands the token back for later use.
   */
  public String uploadImportSource(
      final String fileName, final String objType, final String fileExtension, final File file) {
    final var extra = new JsonObject();
    extra.addProperty("obj_type", objType);
    extra.addProperty("file_extension", fileExtension);
    return uploadMedia(fileName, IMPORT_PARENT_TYPE, null, file, extra.toString());
  }

  @SneakyThrows
  private String uploadMedia(
      final String fileName,
      final String parentType,
      final String parentNode,
      final File file,
      final String extra) {
    final var body =
        UploadAllMediaReqBody.newBuilder()
            .fileName(fileName)
            .parentType(parentType)
            .parentNode(parentNode)
            .size((int) file.length())
            .file(file);
    if (extra != null) {
      body.extra(extra);
    }
    final var resp =
        feishu
            .drive()
            .v1()
            .media()
            .uploadAll(UploadAllMediaReq.newBuilder().uploadAllMediaReqBody(body.build()).build());
    if (!resp.success()) {
      log.error(
          "Failed to upload media '{}' for parent node {}: {}, {}",
          fileName,
          parentNode,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to upload media: " + resp.getMsg());
    }
    final var fileToken = resp.getData().getFileToken();
    log.info(
        "Uploaded media '{}' for parent node {}: fileToken={}", fileName, parentNode, fileToken);
    return fileToken;
  }

  /**
   * Uploads a local file into a drive folder and returns the {@code file_token} of the node it
   * became.
   *
   * <p>Which of Feishu's two upload flows is used is decided here rather than by the caller,
   * because the choice is nothing but the file's size: one call under 20 MB, and prepare / part /
   * finish above it. Both produce the same token, so nothing downstream can tell which ran.
   */
  @SneakyThrows
  public String uploadFile(final String fileName, final String folderToken, final File file) {
    final var size = file.length();
    // Refused before it is sent because Feishu's own refusal is 1061002 "params error", which says
    // nothing about the size being zero, and an empty file is a plausible mistake — a path to a
    // file something else is still writing.
    if (size == 0) {
      throw new IllegalArgumentException("Feishu refuses an empty file: " + file);
    }
    if (size > MAX_UPLOAD_SIZE) {
      throw new IllegalArgumentException(
          "The file is "
              + size
              + " bytes, past the "
              + MAX_UPLOAD_SIZE
              + " the upload API can describe");
    }
    return size <= SINGLE_UPLOAD_LIMIT
        ? uploadWholeFile(fileName, folderToken, file)
        : uploadFileInParts(fileName, folderToken, file, (int) size);
  }

  @SneakyThrows
  private String uploadWholeFile(final String fileName, final String folderToken, final File file) {
    final var resp =
        feishu
            .drive()
            .v1()
            .file()
            .uploadAll(
                UploadAllFileReq.newBuilder()
                    .uploadAllFileReqBody(
                        UploadAllFileReqBody.newBuilder()
                            .fileName(fileName)
                            .parentType(DRIVE_PARENT_TYPE)
                            .parentNode(folderToken)
                            .size((int) file.length())
                            .file(file)
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to upload file '{}' to folder {}: {}, {}",
          fileName,
          folderToken,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to upload file: " + resp.getMsg());
    }
    final var fileToken = resp.getData().getFileToken();
    log.info("Uploaded file '{}' to folder {}: fileToken={}", fileName, folderToken, fileToken);
    return fileToken;
  }

  /**
   * The chunked flow, for a file too big for one call.
   *
   * <p>The chunk size is Feishu's to decide and comes back from the pre-upload, so the file is read
   * a block at a time in the order the blocks are numbered rather than being cut up in advance.
   * Sequentially and never concurrently: the upload endpoints are documented as not supporting
   * concurrent calls, and answer 1061045 to a caller that tries.
   */
  @SneakyThrows
  private String uploadFileInParts(
      final String fileName, final String folderToken, final File file, final int size) {

    final var prepareResp =
        feishu
            .drive()
            .v1()
            .file()
            .uploadPrepare(
                UploadPrepareFileReq.newBuilder()
                    .fileUploadInfo(
                        FileUploadInfo.newBuilder()
                            .fileName(fileName)
                            .parentType(DRIVE_PARENT_TYPE)
                            .parentNode(folderToken)
                            .size(size)
                            .build())
                    .build());
    if (!prepareResp.success()) {
      log.error(
          "Failed to prepare the upload of '{}' to folder {}: {}, {}",
          fileName,
          folderToken,
          prepareResp.getCode(),
          prepareResp.getMsg());
      throw new IllegalStateException("Failed to prepare the upload: " + prepareResp.getMsg());
    }
    final var prepared = prepareResp.getData();
    final var uploadId = prepared.getUploadId();
    final var blockSize = prepared.getBlockSize();
    final var blockNum = prepared.getBlockNum();
    log.info(
        "Uploading '{}' to folder {} in {} block(s) of {} bytes, uploadId={}",
        fileName,
        folderToken,
        blockNum,
        blockSize,
        uploadId);

    try (InputStream in = Files.newInputStream(file.toPath())) {
      for (var seq = 0; seq < blockNum; seq++) {
        final var block = in.readNBytes(blockSize);
        // The block count came from the size Feishu was told, so a short read means the file
        // changed under us. Sending what is left would finish an upload of something that is not
        // the file that was asked for.
        if (block.length == 0 || (seq < blockNum - 1 && block.length < blockSize)) {
          throw new IllegalStateException(
              "The file changed while it was being uploaded: block "
                  + seq
                  + " of "
                  + blockNum
                  + " read "
                  + block.length
                  + " of "
                  + blockSize
                  + " bytes");
        }
        uploadPart(uploadId, seq, block);
      }
    }

    final var finishResp =
        feishu
            .drive()
            .v1()
            .file()
            .uploadFinish(
                UploadFinishFileReq.newBuilder()
                    .uploadFinishFileReqBody(
                        UploadFinishFileReqBody.newBuilder()
                            .uploadId(uploadId)
                            .blockNum(blockNum)
                            .build())
                    .build());
    if (!finishResp.success()) {
      log.error(
          "Failed to finish the upload {} of '{}': {}, {}",
          uploadId,
          fileName,
          finishResp.getCode(),
          finishResp.getMsg());
      throw new IllegalStateException("Failed to finish the upload: " + finishResp.getMsg());
    }
    final var fileToken = finishResp.getData().getFileToken();
    log.info("Uploaded file '{}' to folder {}: fileToken={}", fileName, folderToken, fileToken);
    return fileToken;
  }

  /**
   * One block, sent with its checksum.
   *
   * <p>The block goes through a temporary file because the SDK's request body takes a {@code File}
   * and nothing else; it is deleted whatever happens, since a failed upload of a large file would
   * otherwise leave a chunk of it in the temporary directory.
   *
   * <p>The checksum is optional to Feishu and sent anyway: it is what the chunked flow is for. A
   * block that arrives corrupted over the unreliable connection that made chunking necessary is
   * refused with 1062008 rather than becoming part of a file that is silently wrong.
   */
  @SneakyThrows
  private void uploadPart(final String uploadId, final int seq, final byte[] block) {
    final Path part = Files.createTempFile("feishu-upload-part-", ".bin");
    try {
      Files.write(part, block);
      final var resp =
          feishu
              .drive()
              .v1()
              .file()
              .uploadPart(
                  UploadPartFileReq.newBuilder()
                      .uploadPartFileReqBody(
                          UploadPartFileReqBody.newBuilder()
                              .uploadId(uploadId)
                              .seq(seq)
                              .size(block.length)
                              .checksum(adler32(block))
                              .file(part.toFile())
                              .build())
                      .build());
      if (!resp.success()) {
        log.error(
            "Failed to upload block {} of upload {}: {}, {}",
            seq,
            uploadId,
            resp.getCode(),
            resp.getMsg());
        throw new IllegalStateException("Failed to upload block " + seq + ": " + resp.getMsg());
      }
    } finally {
      Files.deleteIfExists(part);
    }
  }

  /** An Adler-32 checksum in the unsigned decimal form Feishu reads it in. */
  private static String adler32(final byte[] bytes) {
    final var checksum = new Adler32();
    checksum.update(bytes);
    return Long.toString(checksum.getValue());
  }

  /**
   * Starts an import of an already uploaded file and returns the ticket to poll it by.
   *
   * <p>{@code folderToken} blank means the drive's root folder, which is what Feishu does with an
   * empty mount key.
   */
  @SneakyThrows
  public String createImportTask(
      final String fileToken,
      final String fileExtension,
      final String type,
      final String fileName,
      final String folderToken) {
    final var resp =
        feishu
            .drive()
            .v1()
            .importTask()
            .create(
                CreateImportTaskReq.newBuilder()
                    .importTask(
                        ImportTask.newBuilder()
                            .fileToken(fileToken)
                            .fileExtension(fileExtension)
                            .type(type)
                            .fileName(fileName)
                            .point(
                                ImportTaskMountPoint.newBuilder()
                                    .mountType(MOUNT_TO_DRIVE)
                                    .mountKey(folderToken == null ? "" : folderToken)
                                    .build())
                            .build())
                    .build());
    if (!resp.success()) {
      log.error(
          "Failed to create import task for file {} as {}: {}, {}",
          fileToken,
          type,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create import task: " + resp.getMsg());
    }
    final var ticket = resp.getData().getTicket();
    log.info("Created import task {} for file {} as {}", ticket, fileToken, type);
    return ticket;
  }

  /** Where one import task has got to, as one request. */
  @SneakyThrows
  public ImportTask getImportTask(final String ticket) {
    final var resp =
        feishu.drive().v1().importTask().get(GetImportTaskReq.newBuilder().ticket(ticket).build());
    if (!resp.success()) {
      log.error("Failed to read import task {}: {}, {}", ticket, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to read import task: " + resp.getMsg());
    }
    final var result = resp.getData().getResult();
    if (result == null) {
      throw new IllegalStateException("Import task " + ticket + " came back without a result");
    }
    return result;
  }

  /** The finished import, waited for; throws when it fails or outlasts {@link #MAX_WAIT}. */
  public ImportTask awaitImport(final String ticket) {
    return await(
        "import",
        ticket,
        () -> getImportTask(ticket),
        ImportTask::getJobStatus,
        ImportTask::getJobErrorMsg);
  }

  /**
   * Starts an export of one cloud document and returns the ticket to poll it by.
   *
   * <p>{@code subId} names the worksheet or data table when a spreadsheet or a base is being
   * exported as CSV, which holds one of them and not the whole document; it is meaningless for
   * every other combination.
   */
  @SneakyThrows
  public String createExportTask(
      final String token, final String type, final String fileExtension, final String subId) {
    final var exportTask =
        ExportTask.newBuilder().token(token).type(type).fileExtension(fileExtension);
    if (subId != null && !subId.isBlank()) {
      exportTask.subId(subId);
    }
    final var resp =
        feishu
            .drive()
            .v1()
            .exportTask()
            .create(CreateExportTaskReq.newBuilder().exportTask(exportTask.build()).build());
    if (!resp.success()) {
      log.error(
          "Failed to create export task for {} {} as {}: {}, {}",
          type,
          token,
          fileExtension,
          resp.getCode(),
          resp.getMsg());
      throw new IllegalStateException("Failed to create export task: " + resp.getMsg());
    }
    final var ticket = resp.getData().getTicket();
    log.info("Created export task {} for {} {} as {}", ticket, type, token, fileExtension);
    return ticket;
  }

  /**
   * Where one export task has got to, as one request.
   *
   * <p>Takes the document's token as well as the ticket because the endpoint does: the ticket alone
   * is not enough to be asked about.
   */
  @SneakyThrows
  public ExportTask getExportTask(final String ticket, final String token) {
    final var resp =
        feishu
            .drive()
            .v1()
            .exportTask()
            .get(GetExportTaskReq.newBuilder().ticket(ticket).token(token).build());
    if (!resp.success()) {
      log.error("Failed to read export task {}: {}, {}", ticket, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to read export task: " + resp.getMsg());
    }
    final var result = resp.getData().getResult();
    if (result == null) {
      throw new IllegalStateException("Export task " + ticket + " came back without a result");
    }
    return result;
  }

  /** The finished export, waited for; throws when it fails or outlasts {@link #MAX_WAIT}. */
  public ExportTask awaitExport(final String ticket, final String token) {
    return await(
        "export",
        ticket,
        () -> getExportTask(ticket, token),
        ExportTask::getJobStatus,
        ExportTask::getJobErrorMsg);
  }

  /**
   * The bytes of a finished export.
   *
   * <p>Feishu deletes the product ten minutes after the task ends, so this is called as soon as the
   * task reports success rather than being left for the caller to do later.
   */
  @SneakyThrows
  public byte[] downloadExportedFile(final String fileToken) {
    final var resp =
        feishu
            .drive()
            .v1()
            .exportTask()
            .download(DownloadExportTaskReq.newBuilder().fileToken(fileToken).build());
    if (!resp.success()) {
      log.error(
          "Failed to download exported file {}: {}, {}", fileToken, resp.getCode(), resp.getMsg());
      throw new IllegalStateException("Failed to download exported file: " + resp.getMsg());
    }
    return resp.getData().toByteArray();
  }

  /**
   * Polls one asynchronous drive task until it says it is done.
   *
   * <p>Written once over both kinds because they differ in nothing that matters here: the same
   * status numbers mean the same things, and only the request that reads them and the type it comes
   * back as are different.
   */
  private <T> T await(
      final String kind,
      final String ticket,
      final Supplier<T> read,
      final Function<T, Integer> status,
      final Function<T, String> errorMessage) {

    final var deadline = System.nanoTime() + MAX_WAIT.toNanos();
    while (true) {
      final var task = read.get();
      final var jobStatus = status.apply(task);
      if (jobStatus != null && jobStatus == JOB_SUCCEEDED) {
        log.info("Feishu {} task {} finished", kind, ticket);
        return task;
      }
      if (jobStatus == null || (jobStatus != JOB_INITIALISING && jobStatus != JOB_PROCESSING)) {
        log.error(
            "Feishu {} task {} failed: status={}, {}",
            kind,
            ticket,
            jobStatus,
            errorMessage.apply(task));
        throw new IllegalStateException(
            "Feishu "
                + kind
                + " failed with status "
                + jobStatus
                + ": "
                + errorMessage.apply(task));
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "Feishu "
                + kind
                + " task "
                + ticket
                + " was still running after "
                + MAX_WAIT.toMinutes()
                + " minutes");
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        // A cancelled run must stop waiting, and must leave the flag set for whatever else on this
        // thread is also being asked to stop.
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the Feishu " + kind, e);
      }
    }
  }
}
