# Writing a FeishuSendRichMessage

`contentJson` is the message's `content` object for the chosen `msgType`. Feishu checks it; when it refuses, the tool answers with Feishu's code, message and the fields it objected to — fix those and send again.

## Message types

- `text` — `{"text": "hello <at user_id=\"ou_xxx\"></at>"}`. `<at user_id="all"></at>` mentions everyone. Plain text, up to 150 KB.
- `post` — rich text with a title, in one or more locales:
  ```json
  {"zh_cn": {"title": "Weekly report", "content": [
    [{"tag": "text", "text": "Done: ", "style": ["bold"]}, {"tag": "a", "text": "the release", "href": "https://example.com"}],
    [{"tag": "at", "user_id": "ou_xxx"}, {"tag": "text", "text": " please review"}],
    [{"tag": "img", "image_key": "/absolute/path/chart.png"}],
    [{"tag": "code_block", "language": "java", "text": "int x = 1;"}],
    [{"tag": "md", "text": "**markdown** paragraph"}]
  ]}}
  ```
  `content` is a list of paragraphs, each a list of nodes. Nodes: `text` (`text`, `style`: bold / underline / lineThrough / italic), `a` (`text`, `href`), `at` (`user_id`, `"all"` for everyone), `img` (`image_key`), `media` (`file_key`, `image_key`), `emotion` (`emoji_type`, e.g. `SMILE`), `hr`, `code_block` (`language`, `text`), `md` (`text`). `img`, `media` and `md` each take a paragraph of their own. Locales: `zh_cn`, `en_us`, `ja_jp`.
- `image` — `{"image_key": "/absolute/path/picture.png"}`.
- `share_chat` — `{"chat_id": "oc_xxx"}`, a group card; the bot has to be in the group.
- `share_user` — `{"user_id": "ou_xxx"}`, a contact card; open_id only.
- `interactive` — a card, below.

Files, audio and video go through FeishuSendFile.

## Images

Wherever an `img_key` or `image_key` goes, give the absolute path of an image in the workspace (what GenerateImage returns works as-is) and it is uploaded and replaced by its key. So is `![alt](/absolute/path)` inside a `markdown` element or an `md` node. URLs are not fetched: download the image into the workspace first.

## Cards (`interactive`)

Always JSON 2.0:

```json
{
  "schema": "2.0",
  "config": {"summary": {"content": "shown in the chat list"}, "width_mode": "fill"},
  "card_link": {"url": "https://example.com"},
  "header": {
    "title": {"tag": "plain_text", "content": "Deployment finished"},
    "subtitle": {"tag": "plain_text", "content": "production · v1.4.2"},
    "template": "green",
    "icon": {"tag": "standard_icon", "token": "done_outlined"},
    "text_tag_list": [{"tag": "text_tag", "text": {"tag": "plain_text", "content": "healthy"}, "color": "green"}]
  },
  "body": {
    "direction": "vertical",
    "padding": "12px 12px 12px 12px",
    "elements": [
      {"tag": "markdown", "content": "All **12** services rolled out. <at id=ou_xxx></at> owns the follow-up."},
      {"tag": "column_set", "horizontal_spacing": "8px", "columns": [
        {"tag": "column", "width": "weighted", "weight": 1, "elements": [
          {"tag": "markdown", "content": "**Duration**\n14 min"}]},
        {"tag": "column", "width": "weighted", "weight": 1, "elements": [
          {"tag": "markdown", "content": "**Errors**\n<font color='green'>0</font>"}]}
      ]},
      {"tag": "hr"},
      {"tag": "table", "page_size": 5, "columns": [
        {"name": "svc", "display_name": "Service", "data_type": "text"},
        {"name": "p99", "display_name": "p99 (ms)", "data_type": "number"}
      ], "rows": [{"svc": "gateway", "p99": 120}, {"svc": "orders", "p99": 87}]},
      {"tag": "img", "img_key": "/absolute/path/latency.png", "alt": {"tag": "plain_text", "content": "latency"}},
      {"tag": "collapsible_panel", "expanded": false,
       "header": {"title": {"tag": "markdown", "content": "Details"}},
       "elements": [{"tag": "markdown", "content": "- step one\n- step two"}]},
      {"tag": "button", "text": {"tag": "plain_text", "content": "Open dashboard"}, "type": "primary",
       "behaviors": [{"type": "open_url", "default_url": "https://example.com/dash"}]}
    ]
  }
}
```

- **header**: `title` is required; `template` colours the header: blue, wathet, turquoise, green, yellow, orange, red, carmine, violet, purple, indigo, grey, default. At most three `text_tag_list` tags are shown. `icon` is a `standard_icon` with a `token`, or a `custom_icon` with an `img_key`.
- **Layout**: `column_set` (columns with `width`: auto / weighted with `weight` / a pixel value; `background_style`), `collapsible_panel`, `interactive_container` (a clickable region; give it `behaviors` of type `open_url`).
- **Display**: `markdown` (the reply markdown: `<at>`, `<font color>`, `<text_tag>`, `<link>`, `<person>`, `<local_datetime>`, tables, code), `div` (`text`, optional `icon`), `img` (`alt` is required), `img_combination` (`combination_mode`: double / triple / bisect / trisect; `img_list` of `{"img_key": ...}`), `table` (`columns` with `data_type`: text / lark_md / options / number / persons / date; `rows`; `page_size` 1–10), `chart` (`chart_spec`, a VChart spec), `person` (`user_id`), `person_list` (`persons`), `hr`.
- **Buttons**: `button` with `type` default / primary / primary_filled / danger / danger_filled / text / primary_text / laser and `behaviors: [{"type": "open_url", "default_url": "..."}]`; `pc_url`, `ios_url`, `android_url` override it per platform.
- Every element may carry `margin` and an `element_id` (starts with a letter; letters, digits and `_`; at most 20 characters; unique in the card).

### What is refused

- **Anything that calls back to this application**: a `form`, a `behaviors` entry other than `open_url`, a `value` on a button or any input, select, picker, checker or overflow. Those callbacks would reach the agent's own buttons — a card could start a run as whoever pressed it — so a card sent this way may open links and nothing else. Ask the person instead of offering a choice on a card.
- **Templates and card entities** (`{"type": "template" | "card", ...}`): send the card JSON itself.

### What Feishu refuses

- A property it does not know: 2.0 rejects unknown keys where 1.0 ignored them. `note` and the `action` module are gone in 2.0 — use a grey `div` with `text_size: notation`, and place buttons directly.
- More than 30 KB of card JSON (code 230025), or more than 200 elements. Long data belongs in a spreadsheet you link to.
- An unknown user id in `<at>` or `person`, an image key that is not this application's, a table row naming a column not declared in `columns`.
