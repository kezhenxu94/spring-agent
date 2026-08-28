一张数据表的各个字段：每个字段的 field_id、field_name、type、ui_type、property，以及它是否为主字段。

**只要不是你刚刚亲手创建的表，在写入记录之前都先调用它。** FeishuCreateBitableRecord 的 fieldsJson
是以字段名为键的，而各个值的形状取决于字段类型，光看名字并不知道某一列是文本、单选还是关联——各类型
分别接受什么，见 FeishuBitableFieldReference。FeishuUpdateBitableField 所需的 field_id 也是从这里
来的。
