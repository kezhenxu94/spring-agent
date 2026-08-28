**这就是读取记录的正确做法**：可以过滤、排序，并只取所要的字段。返回各行及其 record_id、是否还有
后续页，以及用于读取后续页的 page_token。

只传 appToken 和 tableId 调用，它会返回全部内容的第一页，这也是查看一张表里有什么的正确方式。
filterJson 和 sortJson 的形状见 FeishuBitableFilterGuide——写这两者之前请先读它，因为条件的 value
永远是一个字符串数组，而日期用的是一个标记而不是时间戳。各字段的类型从 FeishuListBitableFields 获取。

每页最多 500 行。如果要取的行你已经知道其 id，用 FeishuBatchGetBitableRecords 更省。
