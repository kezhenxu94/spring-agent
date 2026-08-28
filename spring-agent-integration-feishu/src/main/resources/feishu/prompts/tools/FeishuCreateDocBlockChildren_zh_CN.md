在某个父块之下批量创建一组平级的兄弟块。同一次调用里不能有嵌套，最多 50 个块，其中最多 5 个可以是
Sheet。

**它适用于往一篇已经有内容的文档里追加少量平铺内容，比如在末尾加几行。要构建整篇文档的正文，
不要在这里一块一块地拼：请改用 FeishuConvertMarkdownOrHtmlToBlocks 配合
FeishuCreateDocBlockDescendant**，做法见 FeishuDocBlockGuide。childrenJson 里各个块的形状见
FeishuDocBlockContentReference。
