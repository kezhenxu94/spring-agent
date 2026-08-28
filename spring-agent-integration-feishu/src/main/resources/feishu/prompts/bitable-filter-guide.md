The filterJson of FeishuSearchBitableRecords is one object:

{"conjunction": "and", "conditions": [{"field_name": "Status", "operator": "is", "value": ["Doing"]}]}

conjunction is "and" or "or" and is required. Each condition names a field by its **name**, not its id (that is what tells this filter apart from the one a view carries, which FeishuGetBitableView returns with field_id and a scalar value).

Mixing and with or needs the nested form, and one level of nesting is all Feishu supports:

{"conjunction": "and",
 "conditions": [{"field_name": "Done", "operator": "is", "value": ["false"]}],
 "children": [{"conjunction": "or",
               "conditions": [{"field_name": "Owner", "operator": "is", "value": ["ou_a"]},
                              {"field_name": "Owner", "operator": "is", "value": ["ou_b"]}]}]}

At most 50 conditions in one filter, and at most 10 values in one condition.

operator is one of is, isNot, contains, doesNotContain, isEmpty, isNotEmpty, isGreater, isGreaterEqual, isLess, isLessEqual. A date field takes only is, isEmpty, isNotEmpty, isGreater and isLess.

**value is always an array of strings**, whatever the field's type: a number is ["23.4"], a checkbox ["true"], a date a token described below. For isEmpty and isNotEmpty pass an empty array — passing nothing at all is an error rather than a default.

A single select, person, group or link field takes exactly one value with is or isNot. The strings are option names, user ids in whatever user_id_type the call used (open_id by default), chat ids (oc_...) and record ids respectively.

A checkbox takes only is, with ["true"] or ["false"].

An attachment takes only isEmpty and isNotEmpty.

A date, created time or last modified time field takes a token rather than a raw value:
  ["ExactDate", "1702449755000"] — a millisecond timestamp, rounded to midnight in the base's own
                                   time zone
  ["Today"], ["Tomorrow"], ["Yesterday"]
  and, with the is operator only, ["CurrentWeek"], ["LastWeek"], ["CurrentMonth"], ["LastMonth"],
  ["TheLastWeek"] (the past 7 days), ["TheNextWeek"], ["TheLastMonth"] (the past 30 days),
  ["TheNextMonth"]

A formula or lookup field cannot be filtered on at all; filter on the fields it derives from instead.

sortJson is a separate argument, an array of at most 100 entries: [{"field_name": "Due", "desc": false}].

If the base has advanced permissions switched on and the caller cannot manage it, a search returns no rows rather than an error.
