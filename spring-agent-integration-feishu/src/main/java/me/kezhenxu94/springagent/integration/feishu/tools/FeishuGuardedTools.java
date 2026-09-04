package me.kezhenxu94.springagent.integration.feishu.tools;

import com.google.common.base.Strings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which argument of which Feishu tool names something whose access has to be checked, and which
 * tools name nothing.
 *
 * <p>Written down in one place rather than at fifty call sites, because the property worth having
 * is not that every tool checks — it is that no tool can quietly stop checking. A Feishu tool
 * appearing in neither list below is refused outright by {@link FeishuAccessInterceptor}, and
 * {@code FeishuGuardedToolsTest} walks every {@code @Tool} in this package and fails the build for
 * one that is in neither. So a tool added later is off until somebody decides what it may touch,
 * which is the opposite of the usual arrangement where it works and nobody notices it was never
 * guarded.
 *
 * <p>{@link #UNGUARDED} is not "trusted": it is "names nothing on the Feishu side that a person
 * could be shut out of". The chat and message tools are there because they carry chat ids and
 * message ids, which {@code FeishuChatAccess} rules on inside the tools themselves; the reference
 * tools are there because they return prose held in this repository.
 */
final class FeishuGuardedTools {

  /**
   * Feishu's names for the kinds of thing a token can be, as the permission endpoints spell them.
   */
  static final String DOCX = "docx";

  static final String SHEET = "sheet";
  static final String BITABLE = "bitable";
  static final String FILE = "file";
  static final String FOLDER = "folder";
  static final String WIKI = "wiki";

  /**
   * Not one of Feishu's types: a wiki space is not a document and has a member list of its own
   * rather than a collaborator list, so this routes to the other check.
   */
  static final String WIKI_SPACE = "wiki_space";

  /**
   * The tokens a Feishu link carries, so that an argument documented as taking a link can be
   * checked as the thing the link points at.
   */
  private static final Pattern LINK =
      Pattern.compile(
          "/(?<kind>drive/folder|wiki|doc|docx|sheets|base|mindnote|file|slides)/(?<token>[^/?#]+)");

  /**
   * What the path segment of a link calls a thing, against what the permission endpoints call it.
   * Only the three that differ; everything else is already spelled the same both ways.
   */
  private static final Map<String, String> LINK_KINDS =
      Map.of("sheets", SHEET, "base", BITABLE, "drive/folder", FOLDER);

  /**
   * One argument that has to be checked before the call happens.
   *
   * @param argument the name of the tool's parameter, as it appears in the JSON the model writes
   * @param type what the token is, or {@code $otherArgument} to read the type out of a second
   *     parameter — which is how a tool that exports "a document of whichever type you say" is
   *     checked as the type it was told
   * @param required whether a call that leaves the argument out is refused. False for the optional
   *     {@code folderToken} of the creating tools, where leaving it out means the person's own
   *     folder and there is nothing to check.
   */
  record Guarded(String argument, String type, boolean required) {

    Guarded(final String argument, final String type) {
      this(argument, type, true);
    }
  }

  /**
   * Every Feishu tool that takes a token, and what it takes.
   *
   * <p>A tool with more than one is checked on all of them: {@code FeishuListWikiNodes} names both
   * a space and, sometimes, a node within it, and being allowed into one says nothing about the
   * other.
   */
  static final Map<String, List<Guarded>> GUARDED =
      Map.ofEntries(
          // Documents
          Map.entry("FeishuCreateDocument", List.of(new Guarded("folderToken", FOLDER, false))),
          Map.entry("FeishuGetDocumentInfo", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuGetDocumentRawContent", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuListDocBlocks", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuGetDocBlockChildren", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuGetDocBlock", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuWriteDocumentBody", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuCreateDocBlockChildren", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuCreateDocBlockDescendant", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuUpdateDocBlock", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuBatchUpdateDocBlocks", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuDeleteDocBlockChildren", List.of(new Guarded("documentId", DOCX))),
          Map.entry("FeishuUploadDocBlockMedia", List.of(new Guarded("documentId", DOCX))),
          // Spreadsheets
          Map.entry("FeishuCreateSpreadsheet", List.of(new Guarded("folderToken", FOLDER, false))),
          Map.entry("FeishuListSheets", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuAddSheet", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuCopySheet", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuDeleteSheet", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuSheetReadRange", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuSheetBatchReadRanges", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuSheetUpdateRange", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry(
              "FeishuSheetBatchUpdateRanges", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuSheetSetRangeStyle", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuLockSheet", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuUnlockSheet", List.of(new Guarded("spreadsheetToken", SHEET))),
          Map.entry("FeishuGetProtectedRanges", List.of(new Guarded("spreadsheetToken", SHEET))),
          // Bases
          Map.entry("FeishuCreateBitable", List.of(new Guarded("folderToken", FOLDER, false))),
          Map.entry("FeishuGetBitableMeta", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuUpdateBitableMeta", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuListBitableTables", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuCreateBitableTable", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuBatchCreateBitableTables", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuRenameBitableTable", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuDeleteBitableTables", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuListBitableFields", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuListBitableViews", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuGetBitableView", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuCreateBitableView", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuDeleteBitableView", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuSearchBitableRecords", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuBatchGetBitableRecords", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuCreateBitableRecord", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuBatchCreateBitableRecords", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuUpdateBitableRecord", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuBatchUpdateBitableRecords", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuDeleteBitableRecords", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuUploadBitableAttachment", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuCreateBitableField", List.of(new Guarded("appToken", BITABLE))),
          Map.entry("FeishuUpdateBitableField", List.of(new Guarded("appToken", BITABLE))),
          // Wiki
          Map.entry(
              "FeishuGetWikiNodeInfo", List.of(new Guarded("urlOrToken", "$objType|" + WIKI))),
          Map.entry(
              "FeishuListWikiNodes",
              List.of(
                  new Guarded("spaceId", WIKI_SPACE), new Guarded("parentNodeToken", WIKI, false))),
          // The drive itself
          Map.entry("FeishuListDriveFolder", List.of(new Guarded("folderURL", FOLDER))),
          Map.entry("FeishuDownloadDriveFile", List.of(new Guarded("fileToken", FILE))),
          Map.entry("FeishuUploadDriveFile", List.of(new Guarded("folderToken", FOLDER, false))),
          Map.entry("FeishuImportFile", List.of(new Guarded("folderToken", FOLDER, false))),
          Map.entry("FeishuExportDocument", List.of(new Guarded("token", "$type"))));

  /**
   * The Feishu tools that name nothing on the Feishu side, or name something ruled on elsewhere.
   *
   * <p>Three kinds, and the reason differs: the reference tools return prose kept in this
   * repository; the bot tools answer about this application's own identity; and the chat and
   * message tools carry chat and message ids, which are ruled on by {@code FeishuChatAccess} inside
   * the tools themselves, because a chat's member list is the question there rather than a
   * document's collaborators. That last group is checked as much as anything here is — it is simply
   * checked somewhere else, and putting it in this table would ask the drive endpoints about a chat
   * id they have no answer for.
   */
  static final Set<String> UNGUARDED =
      Set.of(
          "FeishuDocBlockGuide",
          "FeishuDocBlockContentReference",
          "FeishuConvertMarkdownOrHtmlToBlocks",
          "FeishuSheetDataFormats",
          "FeishuBitableFieldReference",
          "FeishuBitableFilterGuide",
          "FeishuGetBotInfo",
          "FeishuSearchBots",
          "FeishuMyDriveFolder",
          "FeishuListChats",
          "FeishuGetChat",
          "FeishuListChatMembers",
          "FeishuIsInChat",
          "FeishuRecallMessage",
          "FeishuSendMessage",
          "FeishuSendFile",
          "FeishuDownloadFile",
          "FeishuReadMessage",
          "FeishuReadMessageHistory");

  /** What an argument turned out to name. */
  record Resolved(String token, String type) {}

  /**
   * The token an argument names and what kind of thing it is, whether it was written as a bare
   * token or as the link somebody copied out of their browser.
   *
   * <p>Both, because a tool documented as taking a link is routinely given a token and the other
   * way round, and a check that only understood one of the two would refuse a call Feishu would
   * have accepted — or, worse, hand a whole URL to the permission endpoint and read the failure as
   * a refusal.
   *
   * <p><b>The one implementation of this in the module</b>, used by the guard and by the tools that
   * take a link. That is not tidiness: a guard that resolved a link differently from the tool it
   * guards would check one document and open another, which is the whole failure this exists to
   * prevent.
   *
   * @param declaredType what the caller says it is, which wins over what the link says — a link
   *     carries the kind of page it was copied from, and a wiki node's link and its document's link
   *     are both links to it
   */
  static Resolved resolve(final String urlOrToken, final String declaredType) {
    if (Strings.isNullOrEmpty(urlOrToken)) {
      return new Resolved(urlOrToken, declaredType);
    }
    final var trimmed = urlOrToken.trim();
    final var matcher = LINK.matcher(trimmed);
    if (!matcher.find()) {
      return new Resolved(trimmed, declaredType);
    }
    final var token = matcher.group("token");
    if (!Strings.isNullOrEmpty(declaredType)) {
      return new Resolved(token, declaredType);
    }
    final var kind = matcher.group("kind");
    return new Resolved(token, LINK_KINDS.getOrDefault(kind, kind));
  }

  private FeishuGuardedTools() {}
}
