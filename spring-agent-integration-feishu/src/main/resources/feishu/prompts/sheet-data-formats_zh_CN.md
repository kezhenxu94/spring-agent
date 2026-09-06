飞书电子表格 v2 写入接口（FeishuSheetUpdateRange / FeishuSheetBatchUpdateRanges）接受的单元格类型：

1. 字符串：直接写字符串本身，例如 "一段文字"
2. 数字：直接写数字本身，例如 123
3. 日期：写成数字——整数部分是从 1899-12-30 起算的天数，小数部分是当天时间占 24 小时的比例（1900-01-01 中午是 2.5）。请先用 FeishuSheetSetRangeStyle 把目标单元格设成日期格式（style 的 formatter 参数，例如 "yyyy/MM/dd"）
4. 纯链接：直接写 URL 字符串本身，例如 "http://www.dd.com"
5. 带文字的链接：{"type": "url", "text": "一段文字", "link": "http://www.dd.com"}
6. 邮箱：直接写地址本身，例如 "aaa@aa.com"
7. 公式：{"type": "formula", "text": "=A1"}（不支持跨表格的 IMPORTRANGE）
8. @ 某人：{"type": "mention", "textType": "email", "text": "aaa@aa.com", "notify": true, "grantReadPermission": true}。textType 取 email、openId 或 unionId。异步处理，只能 @ 同一企业内的用户，单次写入最多 50 个
9. @ 某个文档：{"type": "mention", "textType": "fileToken", "text": "shtxxxx", "objType": "sheet"}。objType 取 sheet、doc、slide、bitable 或 mindnote
10. 下拉列表：{"type": "multipleValue", "values": [1, "test"]}。值可以是布尔、字符串或数字，字符串中不能含逗号。下拉选项本身要通过下拉列表接口配置，这些工具目前还不覆盖
11. 用 segmentStyle 做内联样式，字符串、链接、邮箱和 @ 支持，数字和下拉列表不支持：{"bold": true, "italic": true, "strikeThrough": true, "underline": true, "foreColor": "#ff00ff", "fontSize": 20}。用在字符串上时写成 {"type": "text", "text": "string", "segmentStyle": {...}}

传给 FeishuSheetUpdateRange 或 FeishuSheetBatchUpdateRanges 的 values 数组里，每个单元格都可以是上面任意一种：一个裸的字符串或数字，或者上述某种形状的 JSON 对象。

上面这些数值格式（包括日期）都依赖单元格自身的数字格式。单元格的样式而非内容——字体、颜色、边框、对齐、数字格式——要用 FeishuSheetSetRangeStyle 单独设置：写内容和设样式是两回事。
