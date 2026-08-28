往一张数据表里新增一条记录，并把它连同分配到的 record_id 一起返回。

fieldsJson 是一个以**字段名**为键的对象，而每个值的形状取决于该字段的类型——日期是毫秒时间戳，人员是
[{"id": "ou_..."}]，关联是一个记录 id 数组。全部形状见 FeishuBitableFieldReference，而这张表的每一列
分别是什么类型，由 FeishuListBitableFields 给出。没有出现在 fieldsJson 里的字段会留空，而不是取默认
值。

要新增多条记录，用 FeishuBatchCreateBitableRecords 一次调用就够了，不必调多次。
