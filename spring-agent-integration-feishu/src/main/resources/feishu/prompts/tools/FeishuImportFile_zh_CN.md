把一个本地文件转换成飞书云文档：Word、Markdown、纯文本或 HTML 文件变成文档（type 为 docx），
Excel 或 CSV 文件变成电子表格（type 为 sheet）或多维表格（type 为 bitable）。返回新文档的 token
和链接，把链接给提问的人即可。

文件自身的扩展名决定它能变成什么——文档接受 docx、doc、txt、md、mark、markdown、html；电子表格
接受 xlsx、xls、csv；多维表格接受 xlsx、csv——扩展名与实际内容不符的文件会被飞书拒绝，而不会被
转换。

**truncationCodes 非空说明飞书为了不超出自身上限，已经悄悄丢掉了一部分内容（块、列或单元格太多，
或者图片上传失败）。这时要如实说明，不要报告成一次干净的导入。**
