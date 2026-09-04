package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.service.drive.v1.model.BaseMember;
import com.lark.oapi.service.drive.v1.model.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The per-person folder: that everybody gets their own, that it becomes theirs, and that the bot
 * keeps its way into it.
 *
 * <p>The security-relevant property is the first one. {@code FeishuDriveAccess} lets a person
 * straight into whatever this class calls their folder, with no collaborator list consulted — so a
 * resolution that returned somebody else's folder would be an unchecked way into it. Hence the
 * cases about matching by name exactly, about two people not sharing a resolution, and about a
 * duplicate being resolved the same way in every process.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuUserFoldersTest {

  private static final String ASKER = "ou_asker";
  private static final String STRANGER = "ou_stranger";
  private static final String ROOT = "fldROOT";

  @Mock private FeishuDriveService driveService;
  @Mock private FeishuPermissionTools permissionTools;

  private FeishuUserFolders folders;

  @BeforeEach
  void setUp() {
    when(driveService.rootFolderToken()).thenReturn(ROOT);
    folders = new FeishuUserFolders(driveService, permissionTools);
  }

  private static ToolContext contextOf(final String userId) {
    return new ToolContext(Map.of(ToolContexts.KEY_USER_ID, userId));
  }

  private static File folder(final String name, final String token, final String created) {
    final var file = new File();
    file.setName(name);
    file.setToken(token);
    file.setType("folder");
    file.setCreatedTime(created);
    return file;
  }

  @Test
  @DisplayName("creates the folder, grants the person full_access and hands them ownership")
  void createsAndHandsOver() {
    when(driveService.listFolderFiles(ROOT)).thenReturn(List.of());
    when(driveService.createFolder(ROOT, ASKER)).thenReturn("fldASKER");

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldASKER");

    final var members = ArgumentCaptor.forClass(BaseMember.class);
    final var order = inOrder(driveService, permissionTools);
    order.verify(driveService).createFolder(ROOT, ASKER);
    // Granted before the handover: transferOwner needs the new owner to be a collaborator already.
    order.verify(permissionTools).grant(eq("fldASKER"), eq("folder"), members.capture());
    order.verify(driveService).transferOwner("fldASKER", "folder", ASKER);

    assertThat(members.getValue().getMemberId()).isEqualTo(ASKER);
    assertThat(members.getValue().getMemberType()).isEqualTo("openid");
    assertThat(members.getValue().getPerm()).isEqualTo("full_access");
  }

  @Test
  @DisplayName("reuses the folder it finds in the bot's space rather than making a second one")
  void reusesAnExistingFolder() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(List.of(folder(ASKER, "fldEXISTING", "1000")));

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldEXISTING");

    verify(driveService, never()).createFolder(any(), any());
    verify(driveService, never()).transferOwner(any(), any(), any());
    verifyNoInteractions(permissionTools);
  }

  @Test
  @DisplayName("resolves once per person, however many times it is asked")
  void resolvesOncePerPerson() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(List.of(folder(ASKER, "fldEXISTING", "1000")));

    folders.folderFor(contextOf(ASKER));
    folders.folderFor(contextOf(ASKER));
    folders.folderFor(contextOf(ASKER));

    verify(driveService, times(1)).listFolderFiles(ROOT);
  }

  @Test
  @DisplayName("gives two people two different folders")
  void twoPeopleGetTwoFolders() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(
            List.of(folder(ASKER, "fldASKER", "1000"), folder(STRANGER, "fldOTHER", "1001")));

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldASKER");
    assertThat(folders.folderFor(contextOf(STRANGER))).isEqualTo("fldOTHER");
  }

  @Test
  @DisplayName("matches the name exactly, so a lookalike folder is not taken for theirs")
  void matchesTheNameExactly() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(
            List.of(
                folder(ASKER + "_old", "fldWRONG", "1000"),
                folder("ou_ask", "fldSHORTER", "1001"),
                folder(ASKER.toUpperCase(java.util.Locale.ROOT), "fldUPPER", "1002")));
    when(driveService.createFolder(ROOT, ASKER)).thenReturn("fldNEW");

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldNEW");
  }

  @Test
  @DisplayName("ignores a document that merely happens to be named after them")
  void ignoresANonFolderOfTheSameName() {
    final var document = new File();
    document.setName(ASKER);
    document.setToken("doccnNOTAFOLDER");
    document.setType("docx");
    when(driveService.listFolderFiles(ROOT)).thenReturn(List.of(document));
    when(driveService.createFolder(ROOT, ASKER)).thenReturn("fldNEW");

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldNEW");
  }

  @Test
  @DisplayName("picks the earliest of two folders with the same name, as every process would")
  void aDuplicateIsResolvedTheSameWayEverywhere() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(
            List.of(
                folder(ASKER, "fldSECOND", "2000"),
                folder(ASKER, "fldFIRST", "1000"),
                folder(ASKER, "fldTHIRD", "3000")));

    assertThat(folders.folderFor(contextOf(ASKER))).isEqualTo("fldFIRST");
  }

  @Test
  @DisplayName("a run with nobody behind it has no folder, and says so rather than picking one")
  void aRunWithNoUserHasNoFolder() {
    assertThatThrownBy(() -> folders.folderFor(new ToolContext(Map.of())))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> folders.folderFor(contextOf("")))
        .isInstanceOf(IllegalStateException.class);

    verify(driveService, never()).createFolder(any(), any());
  }

  @Test
  @DisplayName("a failed handover is not swallowed, so a folder is never left looking like theirs")
  void aFailedHandoverPropagates() {
    when(driveService.listFolderFiles(ROOT)).thenReturn(List.of());
    when(driveService.createFolder(ROOT, ASKER)).thenReturn("fldASKER");
    org.mockito.Mockito.doThrow(new IllegalStateException("transfer refused"))
        .when(driveService)
        .transferOwner("fldASKER", "folder", ASKER);

    assertThatThrownBy(() -> folders.folderFor(contextOf(ASKER)))
        .isInstanceOf(IllegalStateException.class);
    // And nothing is remembered, so the next attempt resolves again rather than handing back a
    // folder whose ownership was never moved.
    assertThat(folders.isOwnFolder(contextOf(ASKER), "fldASKER")).isFalse();
  }

  @Test
  @DisplayName("only ever creates one folder per person, even when runs land at the same moment")
  void concurrentRunsCreateOneFolder() throws Exception {
    when(driveService.listFolderFiles(ROOT)).thenReturn(List.of());
    when(driveService.createFolder(ROOT, ASKER)).thenReturn("fldASKER");

    try (final var pool = Executors.newFixedThreadPool(8)) {
      final List<Callable<String>> calls =
          IntStream.range(0, 32)
              .<Callable<String>>mapToObj(i -> () -> folders.folderFor(contextOf(ASKER)))
              .toList();
      for (final var future : pool.invokeAll(calls)) {
        assertThat(future.get()).isEqualTo("fldASKER");
      }
    }

    verify(driveService, times(1)).createFolder(ROOT, ASKER);
  }

  @Test
  @DisplayName("isOwnFolder says yes only about the folder it resolved for that person")
  void isOwnFolderIsPerPersonAndPerToken() {
    when(driveService.listFolderFiles(ROOT))
        .thenReturn(
            List.of(folder(ASKER, "fldASKER", "1000"), folder(STRANGER, "fldOTHER", "1001")));

    folders.folderFor(contextOf(ASKER));
    folders.folderFor(contextOf(STRANGER));

    assertThat(folders.isOwnFolder(contextOf(ASKER), "fldASKER")).isTrue();
    assertThat(folders.isOwnFolder(contextOf(ASKER), "fldOTHER")).isFalse();
    assertThat(folders.isOwnFolder(contextOf(STRANGER), "fldASKER")).isFalse();
    assertThat(folders.isOwnFolder(contextOf(STRANGER), "fldOTHER")).isTrue();
  }

  @Test
  @DisplayName("isOwnFolder never resolves a folder itself, so asking cannot create one")
  void isOwnFolderDoesNotResolve() {
    assertThat(folders.isOwnFolder(contextOf(ASKER), "fldANYTHING")).isFalse();

    verifyNoInteractions(permissionTools);
    verify(driveService, never()).createFolder(any(), any());
    verify(driveService, never()).listFolderFiles(any());
  }

  @Test
  @DisplayName("isOwnFolder says no to a blank token and to a run with nobody behind it")
  void isOwnFolderIsBlankSafe() {
    assertThat(folders.isOwnFolder(contextOf(ASKER), null)).isFalse();
    assertThat(folders.isOwnFolder(contextOf(ASKER), "")).isFalse();
    assertThat(folders.isOwnFolder(new ToolContext(Map.of()), "fldANYTHING")).isFalse();
  }
}
