往一个多维表格里新增一张数据表，可以在创建时就把字段和首个视图的名称一并设好。

fieldsJson 和 defaultViewName 是配套的：要么都传，要么都不传。fieldsJson 的每一项形如
{"field_name": ..., "type": <数字>, "ui_type": ...}，并且可以带一个 property 对象——单选字段的选项、
关联字段的目标数据表等等。各种类型及其各自接受的 property 见 FeishuBitableFieldReference。

数据表名称长度为 1 到 100 个字符，且不能包含 / \\ ? * : [ 或 ]。一个多维表格里的数据表和仪表盘合计
最多 100 个。如果只是要一次新增多张表、只给名字，用 FeishuBatchCreateBitableTables 一次调用就够了，
不必调多次。
