package me.kezhenxu94.springagent.integration.feishu.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuGuides;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.docx.FeishuDocumentBodyWriter;
import me.kezhenxu94.springagent.integration.feishu.docx.FeishuDocxService;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@AgentTool
@Component
@RequiredArgsConstructor
public class FeishuDocTools {
  final FeishuDocxService feishuDocxService;
  final FeishuDocumentBodyWriter feishuDocumentBodyWriter;
  final UserWorkspaceFactory userWorkspaceFactory;
  final FeishuDriveService feishuDriveService;
  final JsonMapper objectMapper;
  final FeishuProperties feishuProperties;
  final FeishuPermissionTools feishuPermissionTools;

  /** The reference pages this class hands back, in the workspace's language. */
  final FeishuGuides guides;

  @Builder
  @Jacksonized
  public static record CreatedDocument(
      String documentId, Integer revisionId, String title, String url) {}

  @Builder
  @Jacksonized
  public static record DocumentInfo(String documentId, Integer revisionId, String title) {}

  @Tool(
      name = "FeishuCreateDocument",
      description =
          "Create a new Feishu document (docx), empty, with no body. The documentId it returns is"
              + " what the other doc tools take, and the url is the link to reply to the user with"
              + " once the document is finished — after the body is written, if a body is coming.\n"
              + "**To write that body, write it as Markdown and hand it to"
              + " FeishuWriteDocumentBody**, which converts and inserts it, images and all, in one"
              + " call. Do not assemble it block by block. Creating a"
              + " document from an existing one, a template say, needs the drive copy endpoint,"
              + " which these tools do not cover yet.")
  @SneakyThrows
  public CreatedDocument createDocument(
      @ToolParam(description = "Document title") String title,
      @ToolParam(
              description = "Token of the folder to create it in; the default folder when left out",
              required = false)
          String folderToken,
      ToolContext toolContext) {
    final var targetFolderToken =
        folderToken == null || folderToken.isBlank()
            ? FeishuFileConstants.DEFAULT_FOLDER_TOKEN
            : folderToken;
    final var document = feishuDocxService.createDocument(targetFolderToken, title);
    feishuPermissionTools.grantDefaultPermissions(toolContext, document.getDocumentId(), "docx");
    return CreatedDocument.builder()
        .documentId(document.getDocumentId())
        .revisionId(document.getRevisionId())
        .title(document.getTitle())
        .url("https://" + feishuProperties.tenantDomain() + "/docx/" + document.getDocumentId())
        .build();
  }

  @Tool(
      name = "FeishuGetDocumentInfo",
      description =
          "The title and latest revisionId of a document. That revisionId is what the writing"
              + " tools take as documentRevisionId, for optimistic concurrency — though passing -1"
              + " means the latest version and saves the call.")
  public DocumentInfo getDocumentInfo(
      @ToolParam(description = "The document_id identifying the Feishu document")
          String documentId) {
    final var document = feishuDocxService.getDocumentInfo(documentId);
    return DocumentInfo.builder()
        .documentId(document.getDocumentId())
        .revisionId(document.getRevisionId())
        .title(document.getTitle())
        .build();
  }

  @Tool(name = "FeishuGetDocumentRawContent", description = "The plain text of a Feishu document.")
  public String getDocumentRawContent(
      @ToolParam(description = "The document_id identifying the Feishu document")
          String documentId) {
    return feishuDocxService.getDocumentRawContent(documentId);
  }

  @Tool(
      name = "FeishuListDocBlocks",
      description =
          "Every block of a document, a page at a time, as a flat list: the nesting is not in the"
              + " order but in each block's parent_id and children fields. This is the main way to"
              + " find a block_id, which FeishuGetDocBlockChildren, FeishuGetDocBlock,"
              + " FeishuUpdateDocBlock and FeishuDeleteDocBlockChildren all need before they can do"
              + " anything.")
  @SneakyThrows
  public JsonNode listDocBlocks(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Page marker: the pageToken the previous call returned",
              required = false)
          String pageToken,
      @ToolParam(
              description = "How many per page; 500 both by default and at most",
              required = false)
          Integer pageSize) {
    final var json =
        feishuDocxService.listDocumentBlocks(documentId, documentRevisionId, pageToken, pageSize);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuGetDocBlockChildren",
      description =
          "The children of a block. With withDescendants true it returns the block and all of its"
              + " descendants as a pre-order tree; false, the default, returns only its immediate"
              + " children. FeishuListDocBlocks returns the block_id, and passing documentId itself"
              + " as blockId gets the children of the document's root block.")
  @SneakyThrows
  public JsonNode getDocBlockChildren(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(description = "block_id of the parent; documentId itself means the root block")
          String blockId,
      @ToolParam(
              description =
                  "Return every descendant, the whole subtree; false by default, which"
                      + " returns only immediate children",
              required = false)
          Boolean withDescendants,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Page marker: the pageToken the previous call returned",
              required = false)
          String pageToken,
      @ToolParam(
              description = "How many per page; 500 both by default and at most",
              required = false)
          Integer pageSize) {
    final var json =
        feishuDocxService.getDocumentBlockChildren(
            documentId, blockId, withDescendants, documentRevisionId, pageToken, pageSize);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuGetDocBlock",
      description =
          "One block in full. FeishuListDocBlocks and FeishuGetDocBlockChildren both return the"
              + " block_id.")
  @SneakyThrows
  public JsonNode getDocBlock(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(description = "The block_id") String blockId,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId) {
    final var json = feishuDocxService.getDocumentBlock(documentId, blockId, documentRevisionId);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuWriteDocumentBody",
      description =
          "**The way to put content into a Feishu document.** Give it Markdown or HTML and it"
              + " writes the whole body: it converts the content, inserts every block, splits the"
              + " insert up when the content is long enough to need it, and uploads and puts in"
              + " place every image the content names — whether by URL or by the absolute path of"
              + " a file in your workspace.\n"
              + "What comes back is a summary, not the blocks: how many blocks were written, how"
              + " many images went in, anything that went wrong with one, and the real block_id of"
              + " each top-level block in case you want to change one afterwards.\n"
              + "Use this instead of FeishuConvertMarkdownOrHtmlToBlocks followed by"
              + " FeishuCreateDocBlockDescendant, which is the same thing done by hand and costs"
              + " you the whole block tree twice. Reach for those two only when you need to build"
              + " blocks that Markdown and HTML cannot express. This appends by default, so it also"
              + " adds a section to a document that already has content.")
  public FeishuDocumentBodyWriter.WrittenBody writeDocumentBody(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(description = "Either markdown or html") String contentType,
      @ToolParam(description = "The Markdown or HTML to write") String content,
      @ToolParam(
              description =
                  "block_id to write under; the document itself, its root block, by default",
              required = false)
          String blockId,
      @ToolParam(
              description = "Where to insert; -1, the default, appends and 0 puts it first",
              required = false)
          Integer index,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot write twice",
              required = false)
          String clientToken,
      ToolContext toolContext) {
    final var home = userWorkspaceFactory.forRequest(toolContext);
    return feishuDocumentBodyWriter.write(
        documentId,
        blockId == null || blockId.isBlank() ? documentId : blockId,
        contentType,
        content,
        index,
        documentRevisionId,
        clientToken,
        source -> localImage(home, source));
  }

  /**
   * The file an image source names, when it names one this run is allowed to read.
   *
   * <p>Anything else — a URL, a relative path, a file outside the workspaces of this request — is
   * not resolved rather than refused, because the content came from the model and a path in it is
   * as likely to be a mistake as an attempt. The image is then reported as one that was left out,
   * which says the same thing without failing a document that is otherwise written.
   */
  private static File localImage(final HomeDir home, final String source) {
    if (source == null
        || source.isBlank()
        || source.contains("://")
        || source.startsWith("data:")) {
      return null;
    }
    try {
      final var path = Path.of(source);
      if (!path.isAbsolute() || !Files.isRegularFile(path)) {
        return null;
      }
      return home.contains(path.toRealPath()) ? path.toFile() : null;
    } catch (InvalidPathException | java.io.IOException e) {
      return null;
    }
  }

  @Tool(
      name = "FeishuCreateDocBlockChildren",
      description =
          "Create a batch of flat sibling blocks under a parent. No nesting in the same call, at"
              + " most 50 blocks, and at most 5 of those a Sheet.\n"
              + "**This is for appending a little flat content to a document that already has some,"
              + " a few lines at the end say. To write a whole document body, or anything you could"
              + " express as Markdown, use FeishuWriteDocumentBody instead** rather than assembling"
              + " it here block by block."
              + " FeishuDocBlockContentReference has the shape of the blocks in childrenJson.")
  @SneakyThrows
  public JsonNode createDocBlockChildren(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(
              description =
                  "block_id of the parent; documentId itself inserts under the document's root"
                      + " block")
          String blockId,
      @ToolParam(
              description =
                  "JSON array of the blocks to create, each one a block object of the shape"
                      + " FeishuDocBlockContentReference describes. Takes a file reference:"
                      + " @file:<path> for a saved tool result, or @file:<path>#/json/pointer for"
                      + " one part of it")
          String childrenJson,
      @ToolParam(
              description = "Where to insert; -1, the default, appends and 0 puts them first",
              required = false)
          Integer index,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot create twice",
              required = false)
          String clientToken) {
    final var json =
        feishuDocxService.createDocumentBlockChildren(
            documentId, blockId, childrenJson, index, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuCreateDocBlockDescendant",
      description =
          "Insert a whole nested tree of blocks in one call, at most 1000 of them. It takes"
              + " whatever FeishuConvertMarkdownOrHtmlToBlocks produced, and anything with nesting"
              + " of its own such as tables or columns.\n"
              + "**Only reach for this to build blocks that Markdown and HTML cannot express.**"
              + " For a document body, FeishuWriteDocumentBody does the conversion, the splitting"
              + " and the images in one call, and without the block tree passing through you"
              + " twice.\n"
              + "In descendantsJson each block carries a temporary block_id of your choosing and"
              + " links to others by them (the children field is an array of those temporary ids);"
              + " childrenId lists the temporary ids that are immediate children of blockId, which"
              + " is normally just the firstLevelBlockIds the conversion returned. The"
              + " blockIdRelations that come back map each temporary id to the real block_id it was"
              + " inserted as.\n"
              + "**If the content has images or attachments, take the real block_ids from there and"
              + " follow the image and attachment workflow in FeishuDocBlockGuide: first"
              + " FeishuUploadDocBlockMedia, then FeishuUpdateDocBlock.** A GridColumn, TableCell"
              + " or Callout has to have at least one child. FeishuDocBlockGuide has the workflow,"
              + " FeishuDocBlockContentReference the block shapes.")
  @SneakyThrows
  public JsonNode createDocBlockDescendant(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(
              description =
                  "block_id of the parent; documentId itself inserts under the document's root"
                      + " block")
          String blockId,
      @ToolParam(
              description =
                  "The temporary block_ids that are immediate children of the parent, normally the"
                      + " firstLevelBlockIds the conversion returned")
          List<String> childrenId,
      @ToolParam(
              description =
                  "JSON array of the nested block tree, normally the blocks the conversion"
                      + " returned. Takes a file reference: where the conversion was too large to"
                      + " show you and was saved to a file, pass @file:<path>#/blocks rather than"
                      + " copying the tree through")
          String descendantsJson,
      @ToolParam(
              description = "Where to insert; -1, the default, appends and 0 puts them first",
              required = false)
          Integer index,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot create twice",
              required = false)
          String clientToken) {
    if (childrenId == null) {
      throw new IllegalArgumentException("childrenId must not be null");
    }
    final var json =
        feishuDocxService.createDocumentBlockDescendant(
            documentId,
            blockId,
            childrenId.toArray(new String[0]),
            descendantsJson,
            index,
            documentRevisionId,
            clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuUpdateDocBlock",
      description =
          "Apply one update to one block: its text or paragraph style, adding, removing or merging"
              + " table rows and columns, adding, removing or resizing grid columns, replacing an"
              + " image or attachment, ticking a todo, and so on.\n"
              + "updateOperationJson has to hold exactly one operation field, such as"
              + " {\"updateTextElements\": {...}} or {\"replaceImage\": {...}}."
              + " FeishuDocBlockContentReference and the Feishu open platform docs have the fields"
              + " of each; the token that replaceImage and replaceFile need comes from"
              + " FeishuUploadDocBlockMedia. To update several blocks, FeishuBatchUpdateDocBlocks"
              + " is cheaper.")
  @SneakyThrows
  public JsonNode updateDocBlock(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(description = "block_id of the block to update") String blockId,
      @ToolParam(description = "The update as JSON, holding exactly one operation field")
          String updateOperationJson,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot update twice",
              required = false)
          String clientToken) {
    final var json =
        feishuDocxService.patchDocumentBlock(
            documentId, blockId, updateOperationJson, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuBatchUpdateDocBlocks",
      description =
          "Update several blocks of a document in one call, at most 200 and no block_id twice,"
              + " which beats calling FeishuUpdateDocBlock repeatedly. requestsJson is an array"
              + " whose elements each carry a blockId and exactly one operation field, of the shape"
              + " FeishuDocBlockContentReference describes.")
  @SneakyThrows
  public JsonNode batchUpdateDocBlocks(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(
              description =
                  "JSON array of the updates, each element a blockId and one operation field."
                      + " Takes a file reference: @file:<path> for a saved tool result, or"
                      + " @file:<path>#/json/pointer for one part of it")
          String requestsJson,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot update twice",
              required = false)
          String clientToken) {
    final var json =
        feishuDocxService.batchUpdateDocumentBlocks(
            documentId, requestsJson, documentRevisionId, clientToken);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuDeleteDocBlockChildren",
      description =
          "Delete the children of a block over the half-open index range [startIndex, endIndex),"
              + " counting from 0.\n"
              + "**This cannot** delete table rows or columns, or grid columns: those need"
              + " FeishuUpdateDocBlock with deleteTableRows, deleteTableColumns or"
              + " deleteGridColumn. Nor can it empty a TableCell, GridColumn or Callout of every"
              + " child. FeishuListDocBlocks returns the block_id.")
  public String deleteDocBlockChildren(
      @ToolParam(description = "The document_id identifying the Feishu document") String documentId,
      @ToolParam(description = "block_id of the parent") String blockId,
      @ToolParam(description = "First index to delete, counting from 0") int startIndex,
      @ToolParam(description = "Index to stop before") int endIndex,
      @ToolParam(
              description = "Document version; -1, the default, means the latest",
              required = false)
          Integer documentRevisionId,
      @ToolParam(
              description = "Idempotency key: pass a UUID so a retry cannot delete twice",
              required = false)
          String clientToken) {
    feishuDocxService.deleteDocumentBlockChildren(
        documentId, blockId, startIndex, endIndex, documentRevisionId, clientToken);
    return "Deleted the children over [" + startIndex + ", " + endIndex + ").";
  }

  @Tool(
      name = "FeishuConvertMarkdownOrHtmlToBlocks",
      description =
          "Turn Markdown or HTML into Feishu document blocks. Nothing is written anywhere: this is"
              + " conversion and nothing else. It handles text, H1 to H9 headings, unordered and"
              + " ordered lists, code blocks, quotes, todos, images, tables and table cells.\n"
              + "**To write a document body, use FeishuWriteDocumentBody instead**: it does this"
              + " conversion and the insert together, and it handles by itself the three things"
              + " doing it by hand means getting right. This tool is for looking at what content"
              + " becomes, or for altering the blocks before they go in.\n"
              + "Doing it by hand: pass blocks as descendantsJson and firstLevelBlockIds as"
              + " childrenId to FeishuCreateDocBlockDescendant (blockId being the target documentId"
              + " itself). Drop the read-only mergeInfo field from each table block\u0027s property"
              + " first, split the insert past 1000 blocks, and for each entry of"
              + " blockIdToImageUrls follow the image workflow in FeishuDocBlockGuide with"
              + " FeishuUploadDocBlockMedia and FeishuUpdateDocBlock. Getting any of the three"
              + " wrong is an error from Feishu that does not say which.")
  @SneakyThrows
  public JsonNode convertMarkdownOrHtmlToBlocks(
      @ToolParam(description = "Either markdown or html") String contentType,
      @ToolParam(description = "The Markdown or HTML to convert") String content) {
    final var json = feishuDocxService.convertToBlocks(contentType, content);
    return objectMapper.readTree(json);
  }

  @Tool(
      name = "FeishuDocBlockGuide",
      description =
          "How to work with the blocks of a Feishu document: the common block_type values, what"
              + " documentRevisionId and clientToken are for, the upload-then-replace workflow for"
              + " images and attachments, and odds and ends such as changing the title and the"
              + " write rate limits. Writing a body is not among them, because"
              + " FeishuWriteDocumentBody does that in one call and needs none of this.\n"
              + "Read it before inserting an image or attachment, changing a document title or"
              + " updating blocks in bulk. For the JSON fields of one particular block_type, read"
              + " FeishuDocBlockContentReference instead.")
  public String getDocBlockGuide() {
    return guides.docBlockGuide();
  }

  @Tool(
      name = "FeishuDocBlockContentReference",
      description =
          "The JSON fields of each Feishu document block's content entity (BlockData) — Image,"
              + " Table, Grid, Callout, File, Sheet, and the text elements that mention a user or"
              + " hold a formula — for hand-assembling the type field that goes with a block_type"
              + " inside childrenJson, descendantsJson, updateOperationJson or requestsJson. Only"
              + " needed where FeishuConvertMarkdownOrHtmlToBlocks cannot reach: exact image"
              + " dimensions, merged cells, column ratios. For the workflow rather than the fields,"
              + " read FeishuDocBlockGuide.")
  public String getDocBlockContentReference() {
    return guides.docBlockContentReference();
  }

  @Tool(
      name = "FeishuUploadDocBlockMedia",
      description =
          "Upload a local image or file and bind it to an Image or File block that already exists"
              + " in the document. This is step two of inserting an image or attachment: step one"
              + " is getting the real block_id of that block, and step three is"
              + " FeishuUpdateDocBlock with replaceImage or replaceFile, putting the fileToken this"
              + " returns into the token field. FeishuDocBlockGuide has the whole workflow.")
  @SneakyThrows
  public String uploadDocBlockMedia(
      @ToolParam(
              description =
                  "Real block_id of the target Image or File block, which becomes the upload's"
                      + " parent_node")
          String blockId,
      @ToolParam(description = "Absolute path of the local file") String filePath,
      @ToolParam(description = "Filename to show") String fileName,
      @ToolParam(description = "docx_image for an image, docx_file for a file or attachment")
          String parentType) {
    final var file = new File(filePath);
    if (!file.isFile()) {
      throw new IllegalArgumentException(
          "filePath does not point to an existing file: " + filePath);
    }
    return feishuDriveService.uploadMedia(fileName, parentType, blockId, file);
  }
}
