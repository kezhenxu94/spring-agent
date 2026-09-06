多维表格的字段有一个数字型的 type，某些 type 内部还有 ui_type 进一步细分。一条记录在某个字段上的取值由该 type 决定形状，而且有好几种类型，写进去的形状和读出来的形状并不一样。FeishuListBitableFields 会告诉你一张表每个字段的 type；写记录之前先调它，不要凭字段名猜。

type / ui_type -> 记录的 fields 映射里放什么

1  文本（ui_type 为 Text、Email 或 Barcode）
   写：直接写字符串本身，"一段文字"
   读：一个 segment 对象的列表，[{"type": "text", "text": "一段文字"}]。segment 也可以是 {"type": "url", "text": "...", "link": "..."} 或 {"type": "mention", "text": "...", "token": "...", "mentionType": "User"|"Docx"|"Sheet"|"Bitable"}。邮箱字段读出来是一个带 mailto: 链接的 url segment。单元格最多 100000 个字符
2  数字（ui_type 为 Number、Progress、Currency 或 Rating）：直接写数字本身，12.5
3  单选：选项名称字符串，"进行中"。写一个还不存在的名称会创建该选项
4  多选：选项名称数组，["a", "b"]。单元格最多 1000 个
5  日期：**毫秒**级 Unix 时间戳，写成数字，1702449755000。不是格式化后的字符串
7  复选框：true 或 false
11 人员：读出来是 [{"id": "ou_...", "name": ..., "en_name": ..., "email": ..., "avatar_url": ...}]，**写进去是 [{"id": "ou_..."}]**——只接受 id。单元格最多 1000 个
13 电话：匹配 (+)?数字 的字符串，最多 64 个字符
15 超链接：一个对象，{"text": "Anthropic", "link": "https://www.anthropic.com"}
17 附件：读出来是 [{"file_token": ..., "name": ..., "type": ..., "size": ..., "url": ..., "tmp_url": ...}]，**写进去是 [{"file_token": "..."}]**。file_token 不是你能编出来、也不是能从别处搬过来的：要用 FeishuUploadBitableAttachment 把本地文件上传到**这个**多维表格里，从而拿到一个。属于其他多维表格或文档的 token 会被拒绝。单元格最多 100 个
18 单向关联 / 21 双向关联：读出来是 {"link_record_ids": ["recA", "recB"]}，**写进去是裸数组 ["recA", "recB"]**，元素是被关联表里的记录 id。单元格最多 500 个
19 查找引用 / 20 公式：只读，{"type": <底层类型>, "value": [...]}。即使底层类型是标量，value 也永远是列表。两者都不能写，也都不能筛选
22 地理位置：读出来是 {"location": "116.39,39.90", "pname": ..., "cityname": ..., "adname": ..., "address": ..., "name": ..., "full_address": ...}，写进去是 "经度,纬度" 字符串
23 群组：读出来是 [{"id": "oc_...", "name": ..., "avatar_url": ...}]，写进去是 [{"id": "oc_..."}]。单元格最多 10 个
1001 创建时间 / 1002 最后更新时间：毫秒时间戳，只读
1003 创建人 / 1004 最后更新人：人员对象，只读
1005 自动编号：字符串，只读

所以给 FeishuCreateBitableRecord 的 fieldsJson 长这样
{"标题": "发布这个版本", "负责人": [{"id": "ou_abc"}], "截止日期": 1702449755000, "状态": "进行中", "标签": ["紧急", "后端"], "已完成": false, "链接": {"text": "PR", "link": "https://..."}}

因此，把一张图片或一个文件写进附件单元格是两步，不是一步：
1. 用 FeishuUploadBitableAttachment，传入该多维表格的 appToken 和本地路径，得到一个 file_token
2. 用 FeishuCreateBitableRecord（或更新、或批量写），把这个 token 放进单元格：{"截图": [{"file_token": "boxcnrHpsg1QDqXAAAyachabcef"}]}
一个单元格放多个文件，就是多次上传加一个 token 数组。

FeishuCreateBitableTable 和 FeishuCreateBitableField 在新建字段时接受的 ui_type 取值有：Text、Email、Barcode、Number、Progress、Currency、Rating、SingleSelect、MultiSelect、DateTime、Checkbox、User、GroupChat、Phone、Url、Attachment、SingleLink、Formula、DuplexLink、Location、CreatedTime、ModifiedTime、CreatedUser、ModifiedUser 和 AutoNumber。查找引用字段（type 19）无法通过 API 创建，也无法更新。

字段自身的设置放在它的 property 对象里，FeishuCreateBitableField、FeishuUpdateBitableField 以及 FeishuCreateBitableTable 的 fieldsJson 都接受它。只有与该类型相称的成员才有意义：

- 单选和多选：{"options": [{"name": "进行中", "color": 0}]}——color 取 0 到 54，创建时不能指定
  选项 id
- 数字、货币、进度和公式：{"formatter": "0"}；货币再加 {"currency_code": "CNY"}（还有 USD、EUR、
  GBP、JPY 等二十多种）
- 进度和评分：{"min": 0, "max": 10}，进度还可加 {"range_customize": true}；评分再加
  {"rating": {"symbol": "star"}}——可取 star、heart、thumbsup、fire、smile、lightning、flower
  或 number
- 日期、创建时间和最后更新时间：{"date_formatter": "yyyy/MM/dd"}——也可以是
  "yyyy-MM-dd HH:mm"、"MM-dd"、"MM/dd/yyyy" 或 "dd/MM/yyyy"；日期字段还可加
  {"auto_fill": false}，表示新记录是否自动填入当前时间
- 人员、单向关联和双向关联：{"multiple": true}，表示单元格能否放多个。关联字段还需要
  {"table_id": "tbl..."} 指明它指向哪张表，双向关联再加 {"back_field_name": "..."}，即它在对面
  那张表上创建的字段名
- 条码：{"allowed_edit_modes": {"manual": true, "scan": true}}
- 地理位置：{"location": {"input_type": "not_limit"}}——或者 "only_mobile"，强制现场定位
- 公式：{"formula_expression": "bitable::$table[tblAbc].$field[fldXyz]*2"}。当
  FeishuGetBitableMeta 报告 formula_type 为 2 时，还必须声明结果类型：
  {"type": {"data_type": 2, "ui_property": {"formatter": "0"}}}
- 自动编号：{"auto_serial": {"type": "auto_increment_number"}}，或者用 "custom" 配上
  {"options": [{"type": "created_time", "value": "yyyyMMdd"}, {"type": "fixed_text", "value": "-"},
  {"type": "system_number", "value": "4"}]}——system_number 的 value 是 1 到 9 位数字，
  fixed_text 最多 20 个字符

表的第一列是它的索引列，只有文本、数字、日期、电话、超链接、公式或地理位置字段能充当索引列。

子记录——也就是表格视图能展示的那棵树——不是一种单独的字段类型。它是一个指向本表自身的关联字段加上一项视图设置：建一个 type 为 18 的字段，其 property 为 {"multiple": true, "table_id": "<这张表自己的 id>"}，写子记录时把该字段设为父记录的记录 id，等视图的 property 带上 {"hierarchy_config": {"field_id": "<那个字段的 id>"}} 之后，层级就显示出来了。字段可以用 FeishuCreateBitableField 建；这些工具不支持修改视图，所以最后那项设置只能在飞书界面里做。

从其他数据源同步过来的表是只读的：在它上面创建、更新和删除记录都会失败。
