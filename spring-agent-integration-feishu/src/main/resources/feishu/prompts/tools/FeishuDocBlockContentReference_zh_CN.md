飞书文档各类块的内容实体（BlockData）各有哪些 JSON 字段——Image、Table、Grid、Callout、File、Sheet，
以及用于 @提及某人 或承载公式的文本元素——供你在 childrenJson、descendantsJson、
updateOperationJson 或 requestsJson 里手工拼出与某个 block_type 配套的 type 字段。只有
FeishuConvertMarkdownOrHtmlToBlocks 覆盖不到的地方才需要它：精确的图片尺寸、合并单元格、分栏比例。
要看流程而不是字段，请读 FeishuDocBlockGuide。
