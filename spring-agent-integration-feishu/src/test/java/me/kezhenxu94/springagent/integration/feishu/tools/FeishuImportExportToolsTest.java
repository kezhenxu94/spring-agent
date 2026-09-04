package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.service.drive.v1.model.ExportTask;
import com.lark.oapi.service.drive.v1.model.ImportTask;
import java.nio.file.Files;
import java.nio.file.Path;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeishuImportExportToolsTest {

  @Mock private FeishuDriveService driveService;
  @Mock private UserWorkspaceFactory userWorkspaceFactory;
  @Mock private FeishuUserFolders userFolders;

  private FeishuImportExportTools tools;

  @TempDir Path workspaceRoot;

  @BeforeEach
  void setUp() {
    lenient()
        .when(userFolders.folderFor(org.mockito.ArgumentMatchers.any()))
        .thenReturn("ou_userOwnFolder");
    tools = new FeishuImportExportTools(driveService, userWorkspaceFactory, userFolders);
  }

  private void workspaceIsTheTempDir() {
    when(userWorkspaceFactory.forRequest(any())).thenReturn(new UserHome(workspaceRoot));
  }

  private Path aFileNamed(final String name) throws Exception {
    final var file = workspaceRoot.resolve(name);
    Files.writeString(file, "hello");
    return file;
  }

  private static ImportTask finishedImport() {
    final var task = new ImportTask();
    task.setType("docx");
    task.setToken("doccnTOKEN");
    task.setUrl("https://example.feishu.cn/docx/doccnTOKEN");
    return task;
  }

  @Test
  @DisplayName("an import uploads the file, starts the task and answers with the new document")
  void importsAFile() throws Exception {
    workspaceIsTheTempDir();
    final var file = aFileNamed("notes.md");
    when(driveService.uploadImportSource("notes.md", "docx", "md", file.toFile()))
        .thenReturn("boxcnSOURCE");
    when(driveService.createImportTask("boxcnSOURCE", "md", "docx", "Notes", "ou_userOwnFolder"))
        .thenReturn("7369583175086912356");
    when(driveService.awaitImport("7369583175086912356")).thenReturn(finishedImport());

    final var imported = tools.importFile(file.toString(), "docx", "Notes", null, null);

    assertThat(imported.token()).isEqualTo("doccnTOKEN");
    assertThat(imported.url()).isEqualTo("https://example.feishu.cn/docx/doccnTOKEN");
    assertThat(imported.truncationCodes()).isEmpty();
  }

  @Test
  @DisplayName("the local file's name stands in for one nobody gave")
  void importsUnderTheLocalName() throws Exception {
    workspaceIsTheTempDir();
    final var file = aFileNamed("notes.md");
    when(driveService.uploadImportSource(any(), any(), any(), any())).thenReturn("boxcnSOURCE");
    when(driveService.createImportTask(any(), any(), any(), any(), any())).thenReturn("ticket");
    when(driveService.awaitImport("ticket")).thenReturn(finishedImport());

    tools.importFile(file.toString(), "docx", "  ", "fldrTOKEN", null);

    verify(driveService).createImportTask("boxcnSOURCE", "md", "docx", "notes.md", "fldrTOKEN");
  }

  @Test
  @DisplayName("what Feishu truncated is reported rather than dropped")
  void reportsTruncation() throws Exception {
    workspaceIsTheTempDir();
    final var file = aFileNamed("rows.xlsx");
    final var task = finishedImport();
    task.setExtra(new String[] {"2000", "2001"});
    when(driveService.uploadImportSource(any(), any(), any(), any())).thenReturn("boxcnSOURCE");
    when(driveService.createImportTask(any(), any(), any(), any(), any())).thenReturn("ticket");
    when(driveService.awaitImport("ticket")).thenReturn(task);

    assertThat(tools.importFile(file.toString(), "sheet", null, null, null).truncationCodes())
        .containsExactly("2000", "2001");
  }

  @Test
  @DisplayName(
      "an extension the target type cannot be made from is refused before anything is sent")
  void refusesAnExtensionTheTypeCannotTake() throws Exception {
    workspaceIsTheTempDir();
    final var file = aFileNamed("notes.md");

    assertThatThrownBy(() -> tools.importFile(file.toString(), "sheet", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xlsx");
  }

  @Test
  @DisplayName("a type that is not a cloud document type is refused")
  void refusesAnUnknownType() throws Exception {
    assertThatThrownBy(() -> tools.importFile("/tmp/notes.md", "mindnote", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("docx, sheet or bitable");
  }

  @Test
  @DisplayName("a file outside the requester's own workspace is never uploaded")
  void refusesAFileOutsideTheWorkspace(@TempDir final Path elsewhere) throws Exception {
    workspaceIsTheTempDir();
    final var file = elsewhere.resolve("secrets.md");
    Files.writeString(file, "hello");

    assertThatThrownBy(() -> tools.importFile(file.toString(), "docx", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspace");
  }

  private static ExportTask finishedExport() {
    final var task = new ExportTask();
    task.setFileName("Quarterly report");
    task.setFileToken("boxcnPRODUCT");
    task.setFileSize(34356);
    return task;
  }

  @Test
  @DisplayName("an export writes the product into the artifacts directory")
  void exportsADocument() throws Exception {
    workspaceIsTheTempDir();
    when(driveService.createExportTask("doccnTOKEN", "docx", "pdf", null)).thenReturn("ticket");
    when(driveService.awaitExport("ticket", "doccnTOKEN")).thenReturn(finishedExport());
    when(driveService.downloadExportedFile("boxcnPRODUCT")).thenReturn("%PDF-1.7".getBytes());

    final var exported = tools.exportDocument("doccnTOKEN", "docx", "pdf", null, null, null);

    final var written = workspaceRoot.resolve("artifacts").resolve("Quarterly report.pdf");
    assertThat(exported.filePath()).isEqualTo(written.toString());
    assertThat(exported.fileName()).isEqualTo("Quarterly report.pdf");
    assertThat(exported.fileSizeBytes()).isEqualTo(34356);
    assertThat(Files.readString(written)).isEqualTo("%PDF-1.7");
  }

  @Test
  @DisplayName("a name that already ends in the extension does not get a second one")
  void doesNotDoubleTheExtension() throws Exception {
    workspaceIsTheTempDir();
    when(driveService.createExportTask(any(), any(), any(), eq("6e5ed3"))).thenReturn("ticket");
    when(driveService.awaitExport(any(), any())).thenReturn(finishedExport());
    when(driveService.downloadExportedFile(any())).thenReturn("a,b".getBytes());

    final var exported =
        tools.exportDocument("shtcnTOKEN", "sheet", "csv", "6e5ed3", "rows.csv", null);

    assertThat(exported.fileName()).isEqualTo("rows.csv");
  }

  @Test
  @DisplayName("a csv export that names no worksheet is refused, since a csv holds only one")
  void refusesACsvWithoutASubId() {
    assertThatThrownBy(() -> tools.exportDocument("shtcnTOKEN", "sheet", "csv", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subId");
  }

  @Test
  @DisplayName("an extension the document type cannot produce is refused")
  void refusesAnExtensionTheDocumentCannotProduce() {
    assertThatThrownBy(() -> tools.exportDocument("doccnTOKEN", "docx", "xlsx", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("docx, pdf");
  }

  @Test
  @DisplayName("a link passed where a token belongs says which tool turns one into the other")
  void refusesALink() {
    assertThatThrownBy(
            () ->
                tools.exportDocument(
                    "https://example.feishu.cn/wiki/wikcnTOKEN", "docx", "pdf", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FeishuGetWikiNodeInfo");
  }
}
