完整地取回一个视图：它的类型、它隐藏了哪些字段、它的子记录层级设置，以及它所应用的过滤条件。

那份过滤条件的写法与 FeishuSearchBitableRecords 所接受的不同——它用 field_id 指代字段，且每个条件的
value 是单个字符串而不是数组。不要把它原样抄进检索用的过滤条件里；检索所用的形式见
FeishuBitableFilterGuide。
