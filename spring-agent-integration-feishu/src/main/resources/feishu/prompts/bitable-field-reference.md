A bitable field has a numeric type and, within some types, a ui_type that narrows it. The value a record carries for a field is shaped by that type, and for several types the shape you write is not the shape you read back. FeishuListBitableFields tells you the type of every field of a table; call it before writing records rather than guessing from a name.

type / ui_type -> what a record's fields map holds

1  Text (ui_type Text, Email or Barcode)
   write: the string itself, "some text"
   read:  a list of segment objects, [{"type": "text", "text": "some text"}]. A segment is also {"type": "url", "text": "...", "link": "..."} or {"type": "mention", "text": "...", "token": "...", "mentionType": "User"|"Docx"|"Sheet"|"Bitable"}. An email field reads back as a url segment with a mailto: link. At most 100,000 characters in a cell
2  Number (ui_type Number, Progress, Currency or Rating): the number itself, 12.5
3  Single select: the option name as a string, "In progress". Writing a name that does not exist yet creates the option
4  Multi select: an array of option names, ["a", "b"]. At most 1,000 in a cell
5  Date: a Unix timestamp in **milliseconds** as a number, 1702449755000. Not a formatted string
7  Checkbox: true or false
11 Person: read as [{"id": "ou_...", "name": ..., "en_name": ..., "email": ..., "avatar_url": ...}], **write as [{"id": "ou_..."}]** — only the id is accepted. At most 1,000 in a cell
13 Phone: a string matching (+)?digits, at most 64 characters
15 Url: an object, {"text": "Anthropic", "link": "https://www.anthropic.com"}
17 Attachment: read as [{"file_token": ..., "name": ..., "type": ..., "size": ..., "url": ..., "tmp_url": ...}], **write as [{"file_token": "..."}]**. A file_token is not something you can make up or carry over from elsewhere: get one from FeishuUploadBitableAttachment, which uploads a local file into *this* base. A token belonging to another base or document is rejected. At most 100 in a cell
18 Single link / 21 Duplex link: read as {"link_record_ids": ["recA", "recB"]}, **write as the plain array ["recA", "recB"]** of record ids in the linked table. At most 500 in a cell
19 Lookup / 20 Formula: read-only, {"type": <the underlying type>, "value": [...]}. The value is always a list even when the underlying type is scalar. Neither can be written and neither can be filtered on
22 Location: read as {"location": "116.39,39.90", "pname": ..., "cityname": ..., "adname": ..., "address": ..., "name": ..., "full_address": ...}, write as the "longitude,latitude" string
23 Group chat: read as [{"id": "oc_...", "name": ..., "avatar_url": ...}], write as [{"id": "oc_..."}]. At most 10 in a cell
1001 Created time / 1002 Last modified time: a millisecond timestamp, read-only
1003 Created by / 1004 Last modified by: a person object, read-only
1005 Auto number: a string, read-only

So a fieldsJson for FeishuCreateBitableRecord looks like
{"Title": "Ship the release", "Owner": [{"id": "ou_abc"}], "Due": 1702449755000, "Status": "Doing", "Tags": ["urgent", "backend"], "Done": false, "Link": {"text": "PR", "link": "https://..."}}

Writing a picture or a file into an attachment cell is therefore two steps, not one:
1. FeishuUploadBitableAttachment with the base's appToken and the local path, which answers with a file_token
2. FeishuCreateBitableRecord (or an update, or a batch write) with that token in the cell: {"Screenshot": [{"file_token": "boxcnrHpsg1QDqXAAAyachabcef"}]}
Several files in one cell means several uploads and one array of tokens.

The ui_type values FeishuCreateBitableTable and FeishuCreateBitableField accept for a new field are Text, Email, Barcode, Number, Progress, Currency, Rating, SingleSelect, MultiSelect, DateTime, Checkbox, User, GroupChat, Phone, Url, Attachment, SingleLink, Formula, DuplexLink, Location, CreatedTime, ModifiedTime, CreatedUser, ModifiedUser and AutoNumber. A lookup field (type 19) can neither be created nor updated through the API.

A field's own settings live in its property object, which FeishuCreateBitableField, FeishuUpdateBitableField and the fieldsJson of FeishuCreateBitableTable all take. Only the members that suit the type mean anything:

- single and multi select: {"options": [{"name": "Doing", "color": 0}]} — color is 0 to 54, and an
  option id may not be set when creating
- number, currency, progress and formula: {"formatter": "0"}; currency adds
  {"currency_code": "CNY"} (USD, EUR, GBP, JPY and about twenty more)
- progress and rating: {"min": 0, "max": 10}, progress also {"range_customize": true}; rating adds
  {"rating": {"symbol": "star"}} — star, heart, thumbsup, fire, smile, lightning, flower or number
- date, created time and last modified time: {"date_formatter": "yyyy/MM/dd"} — also
  "yyyy-MM-dd HH:mm", "MM-dd", "MM/dd/yyyy" or "dd/MM/yyyy"; a date field adds
  {"auto_fill": false}, whether a new record gets the current time
- person, single link and duplex link: {"multiple": true}, whether the cell takes more than one.
  A link field also needs {"table_id": "tbl..."} naming the table it points at, and a duplex link
  {"back_field_name": "..."} for the field it creates on the other side
- barcode: {"allowed_edit_modes": {"manual": true, "scan": true}}
- location: {"location": {"input_type": "not_limit"}} — or "only_mobile", which forces a live fix
- formula: {"formula_expression": "bitable::$table[tblAbc].$field[fldXyz]*2"}. Where
  FeishuGetBitableMeta reports formula_type 2, the result type has to be declared as well:
  {"type": {"data_type": 2, "ui_property": {"formatter": "0"}}}
- auto number: {"auto_serial": {"type": "auto_increment_number"}}, or "custom" with
  {"options": [{"type": "created_time", "value": "yyyyMMdd"}, {"type": "fixed_text", "value": "-"},
  {"type": "system_number", "value": "4"}]} — system_number's value is 1 to 9 digits, fixed_text at
  most 20 characters

A table's first column is its index, and only a text, number, date, phone, url, formula or location field can be one.

Child records, the tree a grid view can show, are not a field type of their own. They are a link field pointing at the table itself plus a view setting: create a field of type 18 whose property is {"multiple": true, "table_id": "<this same table's id>"}, write a child record with that field set to the parent's record id, and the nesting shows up once the view's property carries {"hierarchy_config": {"field_id": "<that field's id>"}}. FeishuCreateBitableField makes the field; these tools do not cover patching a view, so that last setting has to be made in the Feishu UI.

A table synced from another data source is read-only: creating, updating and deleting records all fail on it.
