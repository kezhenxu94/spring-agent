把 Markdown 或 HTML 转换成飞书文档的块。它不会往任何地方写入：这只是转换，别的什么都不做。它支持
文本、H1 到 H9 标题、无序与有序列表、代码块、引用、待办、图片、表格和表格单元格。

**构建一篇新文档的正文，就从这里开始。** 取它返回的 firstLevelBlockIds 和 blocks，把 blocks 作为
descendantsJson、firstLevelBlockIds 作为 childrenId，用 FeishuCreateDocBlockDescendant 一次插入
（blockId 就填目标文档的 documentId 本身）。

有两点要留意。如果结果里有表格，插入前请把每个 table 块的 property 里那个只读的 mergeInfo 字段去掉。
如果有图片，返回的 blockIdToImageUrls 会给出每个临时图片块背后的临时图片地址：插入之后，请照
FeishuDocBlockGuide 里的图片与附件流程，用 FeishuUploadDocBlockMedia 和 FeishuUpdateDocBlock 处理。
另外，超过 1000 个块时，要拆成多次 FeishuCreateDocBlockDescendant 调用来插入。
