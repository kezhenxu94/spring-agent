package me.kezhenxu94.springagent.integration.feishu.tools;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Turning a local file into a cloud document, and a cloud document back into a local file.
 *
 * <p>Both are three calls to Feishu — upload, start, poll for the import; start, poll, download for
 * the export — and each is offered as one tool rather than three because the intermediate steps are
 * not decisions anybody makes. A ticket is of no use to a model on its own, and both halves expire:
 * an uploaded source after five minutes, an export product ten minutes after the task ends. A model
 * that has to remember to come back for either will eventually not.
 */
@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuImportExportTools {

  /**
   * What each kind of cloud document can be made out of, by the extension of the local file.
   *
   * <p>Checked here because Feishu's refusal does not explain itself: importing a .doc as a
   * spreadsheet answers 1069911 "import file type not match", which says nothing about which of the
   * two arguments was wrong or what the other one allows.
   */
  private static final Map<String, List<String>> IMPORTABLE =
      Map.of(
          "docx", List.of("docx", "doc", "txt", "md", "mark", "markdown", "html"),
          "sheet", List.of("xlsx", "xls", "csv"),
          "bitable", List.of("xlsx", "csv"));

  /** What each kind of cloud document can be exported as, for the same reason. */
  private static final Map<String, List<String>> EXPORTABLE =
      Map.of(
          "docx", List.of("docx", "pdf"),
          "doc", List.of("docx", "pdf"),
          "sheet", List.of("xlsx", "csv"),
          "bitable", List.of("xlsx", "csv"));

  /**
   * The exports that carry one sheet or one table rather than the whole document, and so have to be
   * told which one.
   */
  private static final Set<String> NEEDS_SUB_ID = Set.of("sheet", "bitable");

  final FeishuDriveService driveService;
  final UserWorkspaceFactory userWorkspaceFactory;
  final FeishuUserFolders userFolders;
  final FeishuPermissionTools permissionTools;

  /**
   * @param truncationCodes what Feishu dropped to fit its own limits, empty when it dropped nothing
   */
  @Builder
  @Jacksonized
  public static record ImportedDocument(
      String type, String token, String url, List<String> truncationCodes) {}

  @Builder
  @Jacksonized
  public static record ExportedFile(
      String filePath, String fileName, String fileExtension, Integer fileSizeBytes) {}

  @Tool(
      name = "FeishuImportFile",
      description =
          "Turn a local file into a Feishu cloud document: a Word, Markdown, text or HTML file into"
              + " a document (type docx), an Excel or CSV file into a spreadsheet (type sheet) or a"
              + " base (type bitable). Answers with the new document's token and its link, so the"
              + " link is what to give the person who asked.\n"
              + "The file's own extension decides what it can become — docx, doc, txt, md, mark,"
              + " markdown or html for a document; xlsx, xls or csv for a spreadsheet; xlsx or csv"
              + " for a base — and a file whose extension does not match its contents is refused by"
              + " Feishu rather than converted.\n"
              + "**A non-empty truncationCodes means Feishu silently dropped part of the content"
              + " to fit its own limits (too many blocks, columns or cells, or images that failed"
              + " to upload). Say so instead of reporting a clean import.**")
  @SneakyThrows
  public ImportedDocument importFile(
      @ToolParam(description = "Absolute path of the local file to import") final String filePath,
      @ToolParam(
              description =
                  "What it should become: docx for a document, sheet for a spreadsheet, bitable"
                      + " for a base")
          final String type,
      @ToolParam(
              description =
                  "Name for the new cloud document; the local file's name is used when left out",
              required = false)
          final String fileName,
      @ToolParam(
              description =
                  "Token of the drive folder to put it in, as FeishuListDriveFolder's link"
                      + " carries; the folder belonging to whoever you are talking to is used"
                      + " when left out",
              required = false)
          final String folderToken,
      final ToolContext toolContext) {

    if (filePath == null || filePath.isBlank()) {
      throw new IllegalArgumentException("filePath is required");
    }
    final var normalisedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    final var allowed = IMPORTABLE.get(normalisedType);
    if (allowed == null) {
      throw new IllegalArgumentException("type must be one of docx, sheet or bitable, was " + type);
    }

    final var file = new File(filePath);
    if (!file.isFile()) {
      throw new IllegalArgumentException("No file at " + filePath);
    }
    // The same rule the other tools that read a local path apply: what may be uploaded to a chat is
    // what this request's own scopes hold, so that a path is never a way to read someone else's
    // files — or the host's — out of the machine the agent runs on.
    if (!userWorkspaceFactory.forRequest(toolContext).contains(file.toPath())) {
      log.warn("importFile rejected out-of-scope path: {}", filePath);
      throw new IllegalArgumentException("The file must be within an allowed workspace");
    }

    // Taken from the file rather than asked for: Feishu compares the extension it is told against
    // the one the upload carried and refuses the pair when they differ, so there is nothing for a
    // caller to decide and one more way to get it wrong.
    final var extension = extensionOf(file.getName());
    if (!allowed.contains(extension)) {
      throw new IllegalArgumentException(
          "A ."
              + extension
              + " file cannot be imported as "
              + normalisedType
              + "; that takes one of "
              + String.join(", ", allowed));
    }

    final var fileToken =
        driveService.uploadImportSource(file.getName(), normalisedType, extension, file);
    final var ticket =
        driveService.createImportTask(
            fileToken,
            extension,
            normalisedType,
            fileName == null || fileName.isBlank() ? file.getName() : fileName,
            folderToken == null || folderToken.isBlank()
                ? userFolders.folderFor(toolContext)
                : folderToken);

    final var result = driveService.awaitImport(ticket);
    // An imported document is created by the bot like any other, so without this it is one the
    // person who asked for the import cannot open at all.
    permissionTools.handOverToAsker(toolContext, result.getToken(), normalisedType);
    final var truncationCodes =
        result.getExtra() == null ? List.<String>of() : List.of(result.getExtra());
    log.info(
        "Imported '{}' as {} {}, {} truncation code(s)",
        file.getName(),
        normalisedType,
        result.getToken(),
        truncationCodes.size());
    return ImportedDocument.builder()
        .type(result.getType())
        .token(result.getToken())
        .url(result.getUrl())
        .truncationCodes(truncationCodes)
        .build();
  }

  @Tool(
      name = "FeishuExportDocument",
      description =
          "Export a Feishu cloud document to a local file in the artifacts directory: a document"
              + " (type docx or doc) as docx or pdf, a spreadsheet (sheet) or a base (bitable) as"
              + " xlsx or csv. Answers with the path it was written to, which FeishuSendFile then"
              + " sends to the person who asked for it.\n"
              + "Takes the document's token, not its link: for a wiki link call"
              + " FeishuGetWikiNodeInfo first and pass the objToken and objType it answers with. A"
              + " csv holds one worksheet or one data table rather than the whole document, so"
              + " subId is required for it — FeishuListSheets gives the sheetId,"
              + " FeishuListBitableTables the tableId.")
  @SneakyThrows
  public ExportedFile exportDocument(
      @ToolParam(description = "Token of the cloud document to export") final String token,
      @ToolParam(
              description =
                  "What it is: docx or doc for a document, sheet for a spreadsheet, bitable for a"
                      + " base")
          final String type,
      @ToolParam(
              description =
                  "The local file to produce: docx or pdf from a document, xlsx or csv from a"
                      + " spreadsheet or a base")
          final String fileExtension,
      @ToolParam(
              description =
                  "Which worksheet (sheetId) or data table (tableId) the csv holds; required for"
                      + " csv, meaningless otherwise",
              required = false)
          final String subId,
      @ToolParam(
              description = "Name to save it under; the document's own name is used when left out",
              required = false)
          final String fileName,
      final ToolContext toolContext) {

    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token is required");
    }
    if (token.contains("/")) {
      throw new IllegalArgumentException(
          "token is a document token, not a link; call FeishuGetWikiNodeInfo with the link and"
              + " pass the objToken and objType it answers with");
    }
    final var normalisedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    final var allowed = EXPORTABLE.get(normalisedType);
    if (allowed == null) {
      throw new IllegalArgumentException(
          "type must be one of docx, doc, sheet or bitable, was " + type);
    }
    final var extension =
        fileExtension == null ? "" : fileExtension.trim().toLowerCase(Locale.ROOT);
    if (!allowed.contains(extension)) {
      throw new IllegalArgumentException(
          "A "
              + normalisedType
              + " cannot be exported as "
              + fileExtension
              + "; it exports as one of "
              + String.join(", ", allowed));
    }
    if ("csv".equals(extension)
        && NEEDS_SUB_ID.contains(normalisedType)
        && (subId == null || subId.isBlank())) {
      throw new IllegalArgumentException(
          "subId is required to export a "
              + normalisedType
              + " as csv: a csv holds one worksheet"
              + " or one data table, not the whole document");
    }

    final var ticket = driveService.createExportTask(token, normalisedType, extension, subId);
    final var result = driveService.awaitExport(ticket, token);
    // Downloaded straight away on purpose: Feishu deletes the product ten minutes after the task
    // ends, so a path handed back for someone to fetch later is a path that stops working.
    final var bytes = driveService.downloadExportedFile(result.getFileToken());

    final var name =
        nameFor(
            fileName == null || fileName.isBlank() ? result.getFileName() : fileName, extension);
    final var dest = FeishuFiles.artifactPath(name, userWorkspaceFactory.forRequest(toolContext));
    Files.write(dest, bytes);
    log.info("Exported {} {} to {} ({} bytes)", normalisedType, token, dest, bytes.length);

    return ExportedFile.builder()
        .filePath(dest.toString())
        .fileName(dest.getFileName().toString())
        .fileExtension(extension)
        .fileSizeBytes(result.getFileSize())
        .build();
  }

  /** The extension of a filename, lowercased, or the empty string when it has none. */
  static String extensionOf(final String fileName) {
    final var dot = fileName.lastIndexOf('.');
    return dot < 0 || dot == fileName.length() - 1
        ? ""
        : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * The name to save an export under. Feishu answers with the document's name and no extension, and
   * a model asked for a name tends to give one the same way, so the extension is appended unless it
   * is already the one being written.
   */
  static String nameFor(final String fileName, final String extension) {
    final var name = fileName == null || fileName.isBlank() ? "export" : fileName.trim();
    return extensionOf(name).equals(extension) ? name : name + "." + extension;
  }
}
