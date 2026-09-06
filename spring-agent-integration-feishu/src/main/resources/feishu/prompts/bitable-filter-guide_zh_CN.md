FeishuSearchBitableRecords 的 filterJson 是一个对象：

{"conjunction": "and", "conditions": [{"field_name": "状态", "operator": "is", "value": ["进行中"]}]}

conjunction 取 "and" 或 "or"，必填。每个条件用字段**名称**指定字段，不是字段 id（这正是它与视图自带的筛选条件的区别，后者由 FeishuGetBitableView 返回，用的是 field_id 和标量 value）。

and 和 or 混用需要嵌套写法，而飞书只支持一层嵌套：

{"conjunction": "and",
 "conditions": [{"field_name": "已完成", "operator": "is", "value": ["false"]}],
 "children": [{"conjunction": "or",
               "conditions": [{"field_name": "负责人", "operator": "is", "value": ["ou_a"]},
                              {"field_name": "负责人", "operator": "is", "value": ["ou_b"]}]}]}

一个筛选最多 50 个条件，一个条件最多 10 个值。

operator 取以下之一：is、isNot、contains、doesNotContain、isEmpty、isNotEmpty、isGreater、isGreaterEqual、isLess、isLessEqual。日期字段只接受 is、isEmpty、isNotEmpty、isGreater 和 isLess。

**value 永远是字符串数组**，无论字段是什么类型：数字写成 ["23.4"]，复选框写成 ["true"]，日期写成下面说的那种记号。isEmpty 和 isNotEmpty 要传一个空数组——什么都不传会报错，而不是当作默认值。

单选、人员、群组或关联字段配 is 或 isNot 时只能给一个值。这些字符串分别是：选项名称、调用时所用 user_id_type 下的用户 id（默认 open_id）、群 id（oc_...）、记录 id。

复选框只接受 is，值为 ["true"] 或 ["false"]。

附件字段只接受 isEmpty 和 isNotEmpty。

日期、创建时间和最后更新时间字段用记号而不是原始值：
  ["ExactDate", "1702449755000"] — 毫秒时间戳，按多维表格自身时区取整到当天零点
  ["Today"]、["Tomorrow"]、["Yesterday"]
  以及只能配 is 使用的 ["CurrentWeek"]、["LastWeek"]、["CurrentMonth"]、["LastMonth"]、
  ["TheLastWeek"]（过去 7 天）、["TheNextWeek"]、["TheLastMonth"]（过去 30 天）、
  ["TheNextMonth"]

公式字段和查找引用字段完全无法筛选；请改为筛选它们所引用的那些字段。

sortJson 是另一个独立参数，是一个最多 100 项的数组：[{"field_name": "截止日期", "desc": false}]。

如果这个多维表格开了高级权限而调用者没有管理权限，搜索会返回空结果，而不是报错。
