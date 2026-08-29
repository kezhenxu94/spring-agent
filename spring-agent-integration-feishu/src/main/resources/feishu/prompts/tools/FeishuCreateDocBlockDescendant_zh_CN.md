一次调用插入一整棵嵌套的块树，最多 1000 个块。它吃得下 FeishuConvertMarkdownOrHtmlToBlocks 的
产出，也适用于任何自身带嵌套的内容，比如表格或分栏。

**只有当你要拼出 Markdown 和 HTML 表达不了的块时，才用这个工具。** 要写文档正文，
FeishuWriteDocumentBody 一次调用就把转换、分批和图片都做完了，而且整棵块树不必在你这里进出两遍。

在 descendantsJson 里，每个块都带一个由你自己指定的临时 block_id，并用这些临时 id 相互引用
（children 字段就是一个由这些临时 id 组成的数组）；childrenId 列出的是 blockId 的直接子块的那些临时
id，通常就是转换结果里的 firstLevelBlockIds。返回的 blockIdRelations 会把每个临时 id 映射到它实际被
插入成的真实 block_id。

**如果内容里有图片或附件，请从那里取出真实的 block_id，然后照 FeishuDocBlockGuide 里的图片与附件
流程走：先 FeishuUploadDocBlockMedia，再 FeishuUpdateDocBlock。** GridColumn、TableCell 和 Callout
都必须至少有一个子块。流程见 FeishuDocBlockGuide，各种块的形状见 FeishuDocBlockContentReference。
