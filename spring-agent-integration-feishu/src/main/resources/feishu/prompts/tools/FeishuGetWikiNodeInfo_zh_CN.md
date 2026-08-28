查询一个飞书知识库（wiki）节点：它的标题、节点类型、所属知识空间的 id，以及它背后那篇云文档的
token（objToken）和类型（objType）。可以传 wiki 节点链接
（https://xxx.feishu.cn/wiki/xxxxx）、文档链接（.../docx/xxx、.../sheets/xxx），或者裸的 token。

**当 objType 返回 sheet 时，这个节点是一个电子表格：把 objToken 作为 spreadsheetToken 传给表格
类工具（FeishuListSheets、FeishuSheetReadRange、FeishuSheetUpdateRange 等），即可读取或修改它。**
当它是 docx 或 doc 时，把 objToken 作为 documentId 传给文档类工具：要纯文本用
FeishuGetDocumentRawContent，要块结构用 FeishuListDocBlocks 和 FeishuGetDocBlockChildren。当它是
bitable 时，把 objToken 作为 appToken 传给多维表格类工具：用 FeishuListBitableTables 看它有哪些
数据表，用 FeishuSearchBitableRecords 读其中某张表的行。
