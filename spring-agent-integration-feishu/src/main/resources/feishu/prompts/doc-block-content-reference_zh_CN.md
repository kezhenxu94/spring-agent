飞书文档（docx）中每种块的内容实体（BlockData）的 JSON 形状，用于在 childrenJson、descendantsJson、updateOperationJson 或 requestsJson 里手工拼出与 block_type 配套的那个 type 字段。只有在 FeishuConvertMarkdownOrHtmlToBlocks 覆盖不到的地方才需要它——精确的图片尺寸、合并单元格、栏宽比例。普通正文内容应该走 Markdown 或 HTML 转换。

1. 图片（block_type=27）：
{"token": "（只读；由 FeishuUploadDocBlockMedia 之后的 replaceImage 写入）", "width": int, "height": int, "align": 1|2|3（左、中、右）, "caption": {"content": "图注文字"}}

2. 表格（block_type=31）与表格单元格（block_type=32）：
表格的 content 是 {"property": {"row_size": int, "column_size": int, "column_width": [int...], "header_row": boolean, "header_column": boolean}}，它的 children 是各个单元格的 block_id。单元格的 content 是空对象 {}，其 children 可以是任意其他块——文本、列表等等。
**注意**：property 里的 merge_info 是只读的，创建或插入时必须省略。要合并单元格，先创建，再调用带 mergeTableCells 的 FeishuUpdateDocBlock。

3. 分栏（block_type=24）与栏（block_type=25）：
分栏的 content 是 {"column_size": int}，取 2 到 5，它的 children 就是同样数量的栏 block_id。栏的 content 是 {"width_ratio": int}，取 1 到 99，各栏加起来最好等于 100，并且每栏至少要有一个子块。

4. 高亮块（block_type=19）：
{"background_color": enum, "border_color": enum, "text_color": enum, "emoji_id": "emoji 名称，例如 gift"}，至少要有一个子块——一个文本块就够。

5. 文件块（block_type=23）与视图块（block_type=33）：
文件块不能单独存在：它需要一个视图块（{"view_type": 1}，即卡片视图）作为父块。它的 content 是 {"token": "（只读；创建时留空，由 replaceFile 写入）", "name": "文件名", "view_type": 1|2}。

6. 电子表格块（block_type=30）：
创建时只给 {"row_size": int（最多 9）, "column_size": int（最多 9）}；token 是只读的。往里写单元格是电子表格工具的事，不是这些工具的事。

7. 文本块 elements 数组里的特殊元素：
- @ 某个用户：{"mention_user": {"user_id": "该用户的 OpenID"}}——这不会产生通知。
- 公式：{"equation": {"content": "KaTeX"}}。

8. 只读的，或者这些工具创建不了的（知道即可，没有对应的调用）：
多维表格块、流程图、思维笔记、画板、任务、OKR 及其子块，以及 SourceSynced 和 ReferenceSynced 块。它们只能用 FeishuGetDocBlock 或 FeishuListDocBlocks 读取，此外别无他法。
