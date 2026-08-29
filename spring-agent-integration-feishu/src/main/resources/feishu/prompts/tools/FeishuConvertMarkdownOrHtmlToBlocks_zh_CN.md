把 Markdown 或 HTML 转换成飞书文档的块。它不会往任何地方写入：这只是转换，别的什么都不做。它支持
文本、H1 到 H9 标题、无序与有序列表、代码块、引用、待办、图片、表格和表格单元格。

**要写文档正文，请改用 FeishuWriteDocumentBody**：它把这一步转换和插入一起做了，而且手工做时必须
做对的那三件事，它都替你做了。这个工具是用来看看一段内容会变成什么样的，或者在块真正写进去之前
先改一改它们。

手工做的话：把 blocks 作为 descendantsJson、firstLevelBlockIds 作为 childrenId 交给
FeishuCreateDocBlockDescendant（blockId 就填目标文档的 documentId 本身）。之前先把每个 table 块的
property 里那个只读的 mergeInfo 字段去掉；超过 1000 个块时把插入拆成多次；blockIdToImageUrls 里的
每一项，都要照 FeishuDocBlockGuide 里的图片流程，用 FeishuUploadDocBlockMedia 和
FeishuUpdateDocBlock 处理。这三件事里任何一件做错，飞书都会报错，而且不会告诉你错在哪一件。
