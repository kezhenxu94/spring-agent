package me.kezhenxu94.springagent.integration.feishu.tools;

import java.io.File;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
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
  final FeishuDriveService feishuDriveService;
  final JsonMapper objectMapper;
  final FeishuProperties feishuProperties;
  final FeishuPermissionTools feishuPermissionTools;

  @Builder
  @Jacksonized
  public static record CreatedDocument(
      String documentId, Integer revisionId, String title, String url) {}

  @Builder
  @Jacksonized
  public static record DocumentInfo(String documentId, Integer revisionId, String title) {}

  private static final String DOC_BLOCK_GUIDE =
"""
Working with the blocks of a Feishu document (docx).

1. Common block_type values. Every block's JSON carries the shared fields block_id, block_type,
parent_id and children, plus one field named after its own type — text, heading1, table and so on.

1  Page (the document's root block, which is documentId itself)   2  Text   3-11  Heading1-Heading9
12 Bullet (unordered list)   13 Ordered (ordered list)   14 Code   15 Quote
17 Todo   18 Bitable   19 Callout
21 Diagram (flowchart or UML)   22 Divider (its content is the empty object {})
23 File (only ever alongside 33 View)   24 Grid (columns)   25 GridColumn
27 Image   30 Sheet   31 Table
32 TableCell   33 View (the presentation wrapper a File or Sheet sits in)
34 QuoteContainer (its content is the empty object {})
The rest — ChatCard, MindNote, Board, OKR, Task, SourceSynced, ReferenceSynced — are mostly \
read-only or cannot yet be created through these tools; FeishuDocBlockContentReference has the \
detail.

2. Shared parameters.
- documentRevisionId: the document version, used for optimistic concurrency. Pass -1 to work from \
whatever is latest, which is what writes normally want; FeishuGetDocumentInfo returns the current \
revisionId if you need it.
- clientToken: an idempotency key. Generate a fresh UUID per call so a network retry cannot write \
twice.
- A GridColumn, TableCell or Callout has to be created with at least one child, even an empty Text \
block; none of them can be empty.
- For the JSON fields of each block's content — Image, Table, Grid, Callout, File, Sheet — see \
FeishuDocBlockContentReference.

3. The workflow that matters. To write the body of a new document, do **not** assemble it block by \
block with FeishuCreateDocBlockChildren. Instead:
1. FeishuCreateDocument for an empty document, which gives you a documentId (it already has a Page \
root block, so there is nothing to create).
2. FeishuConvertMarkdownOrHtmlToBlocks to turn the Markdown or HTML body into blocks, which gives \
you firstLevelBlockIds and blocks — a tree whose parent/child links are temporary ids.
3. FeishuCreateDocBlockDescendant with those blocks as descendantsJson and firstLevelBlockIds as \
childrenId, which inserts the whole tree under the document's root block in one call (pass \
documentId itself as blockId).
4. If the content has tables, drop the mergeInfo field from each table block's property before \
inserting: it is read-only and inserting it fails.
5. If the content has images or attachments, follow section 4 below.
6. One FeishuCreateDocBlockDescendant inserts at most 1000 blocks; past that, split the call.

FeishuCreateDocBlockChildren is for appending a little flat content to a document that already has \
some — a few lines at the end, say. It takes at most 50 blocks and no nesting.

4. Inserting images and attachments.
1. Get the real block_id of the target Image or File block: either from the blockIdRelations that \
FeishuConvertMarkdownOrHtmlToBlocks plus FeishuCreateDocBlockDescendant return, or by creating an \
empty Image or File block with FeishuCreateDocBlockChildren and taking its block_id. A File block \
gets a parent View block of its own automatically, which is expected.
2. FeishuUploadDocBlockMedia with that block_id as parent_node to upload the local file \
(parentType=docx_image for an image, parentType=docx_file for a file), which returns a fileToken.
3. FeishuUpdateDocBlock on that block_id with replaceImage for an image or replaceFile for a file, \
putting the fileToken in the token field.

5. Odds and ends.
- To change the document title, pass the document token — the Page root block id — as both \
documentId and blockId, and call FeishuUpdateDocBlock with updateTextElements.
- Writes are rate-limited, updating a single block to roughly three times a second. To change \
several blocks, reach for FeishuBatchUpdateDocBlocks rather than a loop over FeishuUpdateDocBlock.
- Creating a Sheet block gets you an empty spreadsheet. Putting data in its cells is the sheet \
tools' job (FeishuSheetTools); these tools do not read or write sheet cells.
""";

  private static final String DOC_BLOCK_CONTENT_REFERENCE =
"""
The JSON shape of each Feishu document (docx) block's content entity (BlockData), for hand-assembling
the type field that goes with a block_type inside childrenJson, descendantsJson,
updateOperationJson or requestsJson. Only needed where FeishuConvertMarkdownOrHtmlToBlocks cannot
reach — exact image dimensions, merged cells, column ratios. Ordinary body content should go through
the Markdown or HTML conversion instead.

1. Image (block_type=27):
{"token": "(read-only; written by replaceImage after FeishuUploadDocBlockMedia)", "width": int, \
"height": int, "align": 1|2|3 (left, centre, right), "caption": {"content": "the caption text"}}

2. Table (block_type=31) and TableCell (block_type=32):
A Table's content is {"property": {"row_size": int, "column_size": int, "column_width": [int...], \
"header_row": boolean, "header_column": boolean}} and its children are TableCell block_ids. A \
TableCell's content is the empty object {}, and its children can be any other blocks — text, \
lists, whatever.
**Note**: merge_info inside property is read-only and has to be left out when creating or \
inserting. To merge cells, create first and then call FeishuUpdateDocBlock with mergeTableCells.

3. Grid (block_type=24) and GridColumn (block_type=25):
A Grid's content is {"column_size": int}, between 2 and 5, and its children are that many \
GridColumn block_ids. A GridColumn's content is {"width_ratio": int}, between 1 and 99 and best \
summing to 100 across the columns, and it needs at least one child.

4. Callout (block_type=19):
{"background_color": enum, "border_color": enum, "text_color": enum, "emoji_id": "an emoji name, \
such as gift"}, with at least one child — a Text block will do.

5. File (block_type=23) with View (block_type=33):
A File block cannot stand alone: it needs a View block ({"view_type": 1}, the card view) as its \
parent. Its content is {"token": "(read-only; left empty at creation, written by replaceFile)", \
"name": "the filename", "view_type": 1|2}.

6. Sheet (block_type=30):
Created with only {"row_size": int (9 at most), "column_size": int (9 at most)}; token is \
read-only. Writing cells is the sheet tools' job, not these tools'.

7. Special elements inside a Text block's elements array:
- Mention a user: {"mention_user": {"user_id": "the user's OpenID"}} — this raises no notification.
- Formula: {"equation": {"content": "KaTeX"}}.

8. Read-only, or not creatable through these tools (worth knowing, but there is nothing to call):
Bitable, Diagram, MindNote, Board, Task, OKR and its child blocks, and the SourceSynced and \
ReferenceSynced blocks. These can be read with FeishuGetDocBlock or FeishuListDocBlocks and \
nothing more.
""";

  @Tool(
      name = "FeishuCreateDocument",
      description =
          "Create a new Feishu document (docx), empty, with no body. The documentId it returns is"
              + " what the other doc tools take, and the url is the link to reply to the user with"
              + " once the document is finished — after the body is written, if a body is coming.\n"
              + "**To write that body, do not assemble it block by block with"
              + " FeishuCreateDocBlockChildren: convert the Markdown or HTML with"
              + " FeishuConvertMarkdownOrHtmlToBlocks and insert it in one call with"
              + " FeishuCreateDocBlockDescendant**, as FeishuDocBlockGuide describes. Creating a"
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
      name = "FeishuCreateDocBlockChildren",
      description =
          "Create a batch of flat sibling blocks under a parent. No nesting in the same call, at"
              + " most 50 blocks, and at most 5 of those a Sheet.\n"
              + "**This is for appending a little flat content to a document that already has some,"
              + " a few lines at the end say. To build a whole document body, do not assemble it"
              + " here block by block: use FeishuConvertMarkdownOrHtmlToBlocks with"
              + " FeishuCreateDocBlockDescendant instead**, as FeishuDocBlockGuide describes."
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
                      + " FeishuDocBlockContentReference describes")
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
          "**The way to write a document body**: insert a whole nested tree of blocks in one call,"
              + " at most 1000 of them. It suits whatever FeishuConvertMarkdownOrHtmlToBlocks"
              + " produced, and anything with nesting of its own such as tables or columns.\n"
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
                      + " returned")
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
                  "JSON array of the updates, each element a blockId and one operation field")
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
              + "**This is where building a new document body starts.** Take the firstLevelBlockIds"
              + " and blocks it returns, pass blocks as descendantsJson and firstLevelBlockIds as"
              + " childrenId, and insert the lot with FeishuCreateDocBlockDescendant (blockId being"
              + " the target documentId itself).\n"
              + "Two things to watch. If the result has tables, drop the read-only mergeInfo field"
              + " from each table block's property before inserting. If it has images, the"
              + " blockIdToImageUrls that come back give the temporary image address behind each"
              + " temporary image block: once inserted, follow the image and attachment workflow in"
              + " FeishuDocBlockGuide with FeishuUploadDocBlockMedia and FeishuUpdateDocBlock. And"
              + " past 1000 blocks, split the insert across several"
              + " FeishuCreateDocBlockDescendant calls.")
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
              + " documentRevisionId and clientToken are for, the way to write a new document's"
              + " body (FeishuConvertMarkdownOrHtmlToBlocks with FeishuCreateDocBlockDescendant"
              + " rather than assembling it by hand with FeishuCreateDocBlockChildren), the"
              + " upload-then-replace workflow for images and attachments, and odds and ends such"
              + " as changing the title and the write rate limits.\n"
              + "Read it before inserting an image or attachment, changing a document title or"
              + " updating blocks in bulk. For the JSON fields of one particular block_type, read"
              + " FeishuDocBlockContentReference instead.")
  public String getDocBlockGuide() {
    return DOC_BLOCK_GUIDE;
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
    return DOC_BLOCK_CONTENT_REFERENCE;
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
