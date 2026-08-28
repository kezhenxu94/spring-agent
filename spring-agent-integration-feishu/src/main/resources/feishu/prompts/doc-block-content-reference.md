The JSON shape of each Feishu document (docx) block's content entity (BlockData), for hand-assembling
the type field that goes with a block_type inside childrenJson, descendantsJson,
updateOperationJson or requestsJson. Only needed where FeishuConvertMarkdownOrHtmlToBlocks cannot
reach — exact image dimensions, merged cells, column ratios. Ordinary body content should go through
the Markdown or HTML conversion instead.

1. Image (block_type=27):
{"token": "(read-only; written by replaceImage after FeishuUploadDocBlockMedia)", "width": int, "height": int, "align": 1|2|3 (left, centre, right), "caption": {"content": "the caption text"}}

2. Table (block_type=31) and TableCell (block_type=32):
A Table's content is {"property": {"row_size": int, "column_size": int, "column_width": [int...], "header_row": boolean, "header_column": boolean}} and its children are TableCell block_ids. A TableCell's content is the empty object {}, and its children can be any other blocks — text, lists, whatever.
**Note**: merge_info inside property is read-only and has to be left out when creating or inserting. To merge cells, create first and then call FeishuUpdateDocBlock with mergeTableCells.

3. Grid (block_type=24) and GridColumn (block_type=25):
A Grid's content is {"column_size": int}, between 2 and 5, and its children are that many GridColumn block_ids. A GridColumn's content is {"width_ratio": int}, between 1 and 99 and best summing to 100 across the columns, and it needs at least one child.

4. Callout (block_type=19):
{"background_color": enum, "border_color": enum, "text_color": enum, "emoji_id": "an emoji name, such as gift"}, with at least one child — a Text block will do.

5. File (block_type=23) with View (block_type=33):
A File block cannot stand alone: it needs a View block ({"view_type": 1}, the card view) as its parent. Its content is {"token": "(read-only; left empty at creation, written by replaceFile)", "name": "the filename", "view_type": 1|2}.

6. Sheet (block_type=30):
Created with only {"row_size": int (9 at most), "column_size": int (9 at most)}; token is read-only. Writing cells is the sheet tools' job, not these tools'.

7. Special elements inside a Text block's elements array:
- Mention a user: {"mention_user": {"user_id": "the user's OpenID"}} — this raises no notification.
- Formula: {"equation": {"content": "KaTeX"}}.

8. Read-only, or not creatable through these tools (worth knowing, but there is nothing to call):
Bitable, Diagram, MindNote, Board, Task, OKR and its child blocks, and the SourceSynced and ReferenceSynced blocks. These can be read with FeishuGetDocBlock or FeishuListDocBlocks and nothing more.
