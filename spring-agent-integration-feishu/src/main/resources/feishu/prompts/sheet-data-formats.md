Cell types the Feishu spreadsheet v2 write endpoints (FeishuSheetUpdateRange /
FeishuSheetBatchUpdateRanges) accept:

1. String: the string itself, e.g. "some text"
2. Number: the number itself, e.g. 123
3. Date: written as a number — whole part is days since 1899-12-30, fractional part is the time as a share of 24 hours (1900-01-01 at noon is 2.5). Set the target cells to a date format with FeishuSheetSetRangeStyle first (style's formatter parameter, e.g. "yyyy/MM/dd")
4. Bare link: the URL string itself, e.g. "http://www.dd.com"
5. Link with text: {"type": "url", "text": "some text", "link": "http://www.dd.com"}
6. Email: the address itself, e.g. "aaa@aa.com"
7. Formula: {"type": "formula", "text": "=A1"} (IMPORTRANGE, which reaches across spreadsheets, is not supported)
8. Mention a person: {"type": "mention", "textType": "email", "text": "aaa@aa.com", "notify": true, "grantReadPermission": true}. textType is email, openId or unionId. Handled asynchronously, only for users of the same tenant, at most 50 per write
9. Mention a document: {"type": "mention", "textType": "fileToken", "text": "shtxxxx", "objType": "sheet"}. objType is sheet, doc, slide, bitable or mindnote
10. Dropdown: {"type": "multipleValue", "values": [1, "test"]}. Values are booleans, strings or numbers, and a string cannot contain a comma. The options themselves have to be configured through the dropdown endpoint, which these tools do not cover yet
11. Inline styling with segmentStyle, which strings, links, emails and mentions accept but numbers and dropdowns do not: {"bold": true, "italic": true, "strikeThrough": true, "underline": true, "foreColor": "#ff00ff", "fontSize": 20}. On a string that reads {"type": "text", "text": "string", "segmentStyle": {...}}

Every cell of the values array passed to FeishuSheetUpdateRange or FeishuSheetBatchUpdateRanges can be any of the above: a bare string or number, or a JSON object of one of these shapes.

The numeric formats above, dates among them, depend on the cell's own number format. Cell styling rather than cell content — font, colour, borders, alignment, number format — is set separately with FeishuSheetSetRangeStyle: writing content and styling it are two different operations.
