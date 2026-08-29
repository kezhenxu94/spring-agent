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
The rest — ChatCard, MindNote, Board, OKR, Task, SourceSynced, ReferenceSynced — are mostly read-only or cannot yet be created through these tools; FeishuDocBlockContentReference has the detail.

2. Shared parameters.
- documentRevisionId: the document version, used for optimistic concurrency. Pass -1 to work from whatever is latest, which is what writes normally want; FeishuGetDocumentInfo returns the current revisionId if you need it.
- clientToken: an idempotency key. Generate a fresh UUID per call so a network retry cannot write twice.
- A GridColumn, TableCell or Callout has to be created with at least one child, even an empty Text block; none of them can be empty.
- For the JSON fields of each block's content — Image, Table, Grid, Callout, File, Sheet — see FeishuDocBlockContentReference.

3. The workflow that matters. To write the body of a new document:
1. FeishuCreateDocument for an empty document, which gives you a documentId (it already has a Page root block, so there is nothing to create).
2. FeishuWriteDocumentBody with the body written as Markdown. That is the whole of it: it converts, inserts, splits the insert when the content is long enough to need it, and uploads and binds every image the content names — by URL, or by the absolute path of a file in your workspace. It hands back counts and the real block_id of each top-level block, not the block tree.

Do **not** assemble a body block by block with FeishuCreateDocBlockChildren, and do not run the conversion and the insert yourself. Doing it by hand means getting three things right that FeishuWriteDocumentBody gets right for you — dropping the read-only mergeInfo from every table block's property, splitting past the 1000 blocks one insert may carry, and the three-step image dance in section 4 — and Feishu answers a mistake in any of them with an error that does not say which.

FeishuConvertMarkdownOrHtmlToBlocks with FeishuCreateDocBlockDescendant remains the way to build blocks that Markdown and HTML cannot express: exact image dimensions, merged cells, column ratios. Reach for it for those, and for nothing else.

FeishuCreateDocBlockChildren is for appending a little flat content to a document that already has some — a few lines at the end, say. It takes at most 50 blocks and no nesting.

4. Inserting images and attachments.
1. Get the real block_id of the target Image or File block: either from the blockIdRelations that FeishuConvertMarkdownOrHtmlToBlocks plus FeishuCreateDocBlockDescendant return, or by creating an empty Image or File block with FeishuCreateDocBlockChildren and taking its block_id. A File block gets a parent View block of its own automatically, which is expected.
2. FeishuUploadDocBlockMedia with that block_id as parent_node to upload the local file (parentType=docx_image for an image, parentType=docx_file for a file), which returns a fileToken.
3. FeishuUpdateDocBlock on that block_id with replaceImage for an image or replaceFile for a file, putting the fileToken in the token field.

5. Odds and ends.
- To change the document title, pass the document token — the Page root block id — as both documentId and blockId, and call FeishuUpdateDocBlock with updateTextElements.
- Writes are rate-limited, updating a single block to roughly three times a second. To change several blocks, reach for FeishuBatchUpdateDocBlocks rather than a loop over FeishuUpdateDocBlock.
- Creating a Sheet block gets you an empty spreadsheet. Putting data in its cells is the sheet tools' job (FeishuSheetTools); these tools do not read or write sheet cells.
