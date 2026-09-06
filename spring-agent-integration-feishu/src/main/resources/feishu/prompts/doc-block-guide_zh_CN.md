操作飞书文档（docx）中的块。

1. 常见的 block_type 取值。每个块的 JSON 都带有公共字段 block_id、block_type、parent_id 和 children，再加上一个以自身类型命名的字段——text、heading1、table 等等。

1  Page（文档根块，它的 id 就是 documentId 本身）   2  Text 文本   3-11  Heading1 到 Heading9 标题
12 Bullet（无序列表）   13 Ordered（有序列表）   14 Code 代码块   15 Quote 引用
17 Todo 待办   18 Bitable 多维表格   19 Callout 高亮块
21 Diagram（流程图或 UML）   22 Divider 分割线（其内容是空对象 {}）
23 File 文件（永远与 33 View 搭配出现）   24 Grid 分栏   25 GridColumn 栏
27 Image 图片   30 Sheet 电子表格   31 Table 表格
32 TableCell 单元格   33 View（包裹 File 或 Sheet 的展示容器）
34 QuoteContainer 引用容器（其内容是空对象 {}）
其余的——群聊卡片、思维笔记、画板、OKR、任务、SourceSynced、ReferenceSynced——大多是只读的，或者这些工具还创建不了；详见 FeishuDocBlockContentReference。

2. 公共参数。
- documentRevisionId：文档版本号，用于乐观并发控制。传 -1 表示基于最新版本操作，写入时通常就要这个；如果确实需要具体版本号，FeishuGetDocumentInfo 会返回当前的 revisionId。
- clientToken：幂等键。每次调用都生成一个新的 UUID，这样网络重试不会写入两次。
- 栏（GridColumn）、单元格（TableCell）和高亮块（Callout）创建时必须至少带一个子块，哪怕是一个空的文本块；它们都不能是空的。
- 各种块 content 的 JSON 字段——图片、表格、分栏、高亮块、文件、电子表格——见 FeishuDocBlockContentReference。

3. 真正要记住的流程。要写一篇新文档的正文：
1. 用 FeishuCreateDocument 建一个空文档，拿到 documentId（它自带 Page 根块，不需要另外创建）。
2. 用 FeishuWriteDocumentBody，正文用 Markdown 写。就这么多：它会完成转换、插入，内容长到需要时自动拆分插入，并把内容中引用的每张图片上传并绑定好——图片可以是 URL，也可以是你工作区里某个文件的绝对路径。它返回的是各项计数和每个顶层块真实的 block_id，而不是块树。

**不要**用 FeishuCreateDocBlockChildren 一块一块地拼正文，也不要自己去跑转换和插入。手工做意味着有三件事要你自己做对，而 FeishuWriteDocumentBody 会替你做对——从每个表格块的 property 里去掉只读的 mergeInfo、把超过单次插入上限 1000 个块的内容拆开、以及第 4 节里那套三步走的图片流程——而这三件事里任何一件出错，飞书都只会回一个不说清楚是哪一件的错误。

FeishuConvertMarkdownOrHtmlToBlocks 配合 FeishuCreateDocBlockDescendant，仍然是构造 Markdown 和 HTML 表达不了的块的办法：精确的图片尺寸、合并单元格、栏宽比例。只在这些场景下用它，别的场景都不要。

FeishuCreateDocBlockChildren 用于往已有内容的文档后面追加少量扁平内容——比如在末尾加几行。它最多接受 50 个块，且不支持嵌套。

4. 插入图片和附件。
1. 拿到目标图片块或文件块真实的 block_id：要么来自 FeishuConvertMarkdownOrHtmlToBlocks 加 FeishuCreateDocBlockDescendant 返回的 blockIdRelations，要么用 FeishuCreateDocBlockChildren 创建一个空的图片块或文件块并取它的 block_id。文件块会自动获得一个属于自己的父级视图块，这是正常的。
2. 用 FeishuUploadDocBlockMedia，以那个 block_id 作为 parent_node 上传本地文件（图片用 parentType=docx_image，文件用 parentType=docx_file），它会返回一个 fileToken。
3. 对那个 block_id 调用 FeishuUpdateDocBlock，图片用 replaceImage、文件用 replaceFile，把 fileToken 放进 token 字段。

5. 零碎事项。
- 要改文档标题，把文档 token——也就是 Page 根块的 id——同时作为 documentId 和 blockId 传入，然后用 updateTextElements 调用 FeishuUpdateDocBlock。
- 写入有频率限制，更新单个块大约每秒三次。要改多个块，请用 FeishuBatchUpdateDocBlocks，而不要循环调用 FeishuUpdateDocBlock。
- 创建电子表格块得到的是一张空表。往单元格里填数据是电子表格工具（FeishuSheetTools）的事；这些文档工具既不读也不写表格单元格。
