在飞书云盘里创建一个多维表格（base），返回它的 token、URL，以及它初始那张数据表的 id。这个
defaultTableId 可以直接用作记录类和视图类工具的 tableId 参数，中间不必再调用
FeishuListBitableTables。

**凡是数据本身有结构、用户之后会想去查询它，就优先用它而不是 FeishuCreateSpreadsheet**：带类型的
字段、有状态要筛选、有人要分组、有截止日期要排序。只有当内容是一堆没人会去筛选的平铺数据时，电子
表格才是更好的答案。无论用哪个，只要要给用户的清单超过十行，就把它放进文件里，回复里只给出链接。

它初始的那张数据表只有一个文本字段：请用 FeishuCreateBitableTable 把结构定好，或者在写入记录之前先
把需要的字段加上。
