覆写一条记录的若干字段。只有在 fieldsJson 里点了名的字段会被改动，其余字段保持原值，而被设为 null 的
字段会被清空。各字段的形状见 FeishuCreateBitableRecord 和 FeishuBitableFieldReference。要更新多条
记录，用 FeishuBatchUpdateBitableRecords 一次调用就够了，不必调多次。
