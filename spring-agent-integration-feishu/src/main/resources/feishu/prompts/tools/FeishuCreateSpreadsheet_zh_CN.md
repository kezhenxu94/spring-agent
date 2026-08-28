在飞书云盘里创建一个电子表格，返回它的 token、URL 和默认工作表 id。这个 defaultSheetId 可以直接用作
FeishuSheetUpdateRange、FeishuSheetBatchUpdateRanges、FeishuSheetReadRange 或
FeishuSheetBatchReadRanges 的 range 参数，中间不必再调用 FeishuListSheets。

凡是要给用户的清单超过十行——比如一份文件列表或一次查询结果——都用它：不要在回复里逐条罗列，而是
建一个电子表格，用 FeishuSheetUpdateRange 把各行写进去，回复里只给出链接。

电子表格是一张单元格网格。当这些行本身有结构、用户之后会想去查询它们时——有状态要筛选、有责任人要
分组、有日期要排序——FeishuCreateBitable 能给出带类型的字段和视图，是更好的选择。
