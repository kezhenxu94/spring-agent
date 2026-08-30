package me.kezhenxu94.springagent.integration.feishu.drive;

import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.service.drive.v1.model.CreateExportTaskReq;
import com.lark.oapi.service.drive.v1.model.CreateImportTaskReq;
import com.lark.oapi.service.drive.v1.model.DownloadExportTaskReq;
import com.lark.oapi.service.drive.v1.model.ExportTask;
import com.lark.oapi.service.drive.v1.model.GetExportTaskReq;
import com.lark.oapi.service.drive.v1.model.GetImportTaskReq;
import com.lark.oapi.service.drive.v1.model.ImportTask;
import com.lark.oapi.service.drive.v1.model.ImportTaskMountPoint;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReq;
import com.lark.oapi.service.drive.v1.model.UploadAllMediaReqBody;
import java.io.File;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

  private final Client feishu;

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
