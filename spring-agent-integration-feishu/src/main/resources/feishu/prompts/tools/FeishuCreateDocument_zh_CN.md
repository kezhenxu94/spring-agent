新建一篇飞书云文档（docx），是空的，没有正文。它返回的 documentId 就是其他文档类工具要用的那个，
url 则是文档做好之后回复给用户的链接——如果还要写正文，就等正文写完再给。

**要写正文，不要用 FeishuCreateDocBlockChildren 一块一块地拼：请用
FeishuConvertMarkdownOrHtmlToBlocks 把 Markdown 或 HTML 转换好，再用
FeishuCreateDocBlockDescendant 一次插入**，做法见 FeishuDocBlockGuide。若要基于已有文档（比如一个
模板）来创建，需要用云盘的复制接口，这套工具目前还没有覆盖。
