# 如何编写 FeishuSendRichMessage

`contentJson` 是所选 `msgType` 对应的消息 `content` 对象。内容由飞书校验；被拒时，工具会返回飞书的错误码、错误信息以及它指出的字段——按这些改正后再发一次。

## 消息类型

- `text` —— `{"text": "你好 <at user_id=\"ou_xxx\"></at>"}`。`<at user_id="all"></at>` 提及所有人。纯文本，最多 150 KB。
- `post` —— 带标题的富文本，可写一种或多种语言：
  ```json
  {"zh_cn": {"title": "周报", "content": [
    [{"tag": "text", "text": "已完成：", "style": ["bold"]}, {"tag": "a", "text": "发布", "href": "https://example.com"}],
    [{"tag": "at", "user_id": "ou_xxx"}, {"tag": "text", "text": " 请审阅"}],
    [{"tag": "img", "image_key": "/absolute/path/chart.png"}],
    [{"tag": "code_block", "language": "java", "text": "int x = 1;"}],
    [{"tag": "md", "text": "**markdown** 段落"}]
  ]}}
  ```
  `content` 是段落列表，每个段落是节点列表。节点：`text`（`text`，`style`：bold / underline / lineThrough / italic）、`a`（`text`、`href`）、`at`（`user_id`，`"all"` 表示所有人）、`img`（`image_key`）、`media`（`file_key`、`image_key`）、`emotion`（`emoji_type`，如 `SMILE`）、`hr`、`code_block`（`language`、`text`）、`md`（`text`）。`img`、`media` 和 `md` 必须各自独占一个段落。语言：`zh_cn`、`en_us`、`ja_jp`。
- `image` —— `{"image_key": "/absolute/path/picture.png"}`。
- `share_chat` —— `{"chat_id": "oc_xxx"}`，群名片；机器人必须在该群里。
- `share_user` —— `{"user_id": "ou_xxx"}`，个人名片；只能用 open_id。
- `interactive` —— 卡片，见下文。

文件、音频和视频请用 FeishuSendFile 发送。

## 图片

凡是填 `img_key` 或 `image_key` 的地方，都可以给出工作区内图片的绝对路径（GenerateImage 返回的路径可以直接用），它会被上传并替换为图片 key。`markdown` 元素或 `md` 节点里的 `![alt](/absolute/path)` 同样如此。不会从 URL 拉取图片：请先把图片下载到工作区。

## 卡片（`interactive`）

一律使用 JSON 2.0：

```json
{
  "schema": "2.0",
  "config": {"summary": {"content": "会话列表里显示的摘要"}, "width_mode": "fill"},
  "card_link": {"url": "https://example.com"},
  "header": {
    "title": {"tag": "plain_text", "content": "部署完成"},
    "subtitle": {"tag": "plain_text", "content": "production · v1.4.2"},
    "template": "green",
    "icon": {"tag": "standard_icon", "token": "done_outlined"},
    "text_tag_list": [{"tag": "text_tag", "text": {"tag": "plain_text", "content": "健康"}, "color": "green"}]
  },
  "body": {
    "direction": "vertical",
    "padding": "12px 12px 12px 12px",
    "elements": [
      {"tag": "markdown", "content": "全部 **12** 个服务已上线。后续由 <at id=ou_xxx></at> 跟进。"},
      {"tag": "column_set", "horizontal_spacing": "8px", "columns": [
        {"tag": "column", "width": "weighted", "weight": 1, "elements": [
          {"tag": "markdown", "content": "**耗时**\n14 分钟"}]},
        {"tag": "column", "width": "weighted", "weight": 1, "elements": [
          {"tag": "markdown", "content": "**错误**\n<font color='green'>0</font>"}]}
      ]},
      {"tag": "hr"},
      {"tag": "table", "page_size": 5, "columns": [
        {"name": "svc", "display_name": "服务", "data_type": "text"},
        {"name": "p99", "display_name": "p99（毫秒）", "data_type": "number"}
      ], "rows": [{"svc": "gateway", "p99": 120}, {"svc": "orders", "p99": 87}]},
      {"tag": "img", "img_key": "/absolute/path/latency.png", "alt": {"tag": "plain_text", "content": "延迟"}},
      {"tag": "collapsible_panel", "expanded": false,
       "header": {"title": {"tag": "markdown", "content": "详情"}},
       "elements": [{"tag": "markdown", "content": "- 第一步\n- 第二步"}]},
      {"tag": "button", "text": {"tag": "plain_text", "content": "打开看板"}, "type": "primary",
       "behaviors": [{"type": "open_url", "default_url": "https://example.com/dash"}]}
    ]
  }
}
```

- **header**：`title` 必填；`template` 决定标题栏颜色：blue、wathet、turquoise、green、yellow、orange、red、carmine、violet、purple、indigo、grey、default。`text_tag_list` 最多显示三个标签。`icon` 可以是带 `token` 的 `standard_icon`，或带 `img_key` 的 `custom_icon`。
- **布局**：`column_set`（各列的 `width`：auto / weighted 配合 `weight` / 像素值；`background_style`）、`collapsible_panel`、`interactive_container`（可点击区域；给它配置 type 为 `open_url` 的 `behaviors`）。
- **展示**：`markdown`（即回复所用的 markdown：`<at>`、`<font color>`、`<text_tag>`、`<link>`、`<person>`、`<local_datetime>`、表格、代码）、`div`（`text`，可选 `icon`）、`img`（`alt` 必填）、`img_combination`（`combination_mode`：double / triple / bisect / trisect；`img_list` 为 `{"img_key": ...}` 列表）、`table`（`columns` 的 `data_type`：text / lark_md / options / number / persons / date；`rows`；`page_size` 1–10）、`chart`（`chart_spec`，VChart 规格）、`person`（`user_id`）、`person_list`（`persons`）、`hr`。
- **按钮**：`button`，`type` 可选 default / primary / primary_filled / danger / danger_filled / text / primary_text / laser，配合 `behaviors: [{"type": "open_url", "default_url": "..."}]`；`pc_url`、`ios_url`、`android_url` 可按平台覆盖。
- 每个元素都可以带 `margin` 和 `element_id`（以字母开头；只含字母、数字和 `_`；不超过 20 个字符；卡片内唯一）。

### 会被拒绝的内容

- **任何会回调本应用的交互**：`form`、type 不是 `open_url` 的 `behaviors`、按钮或任何输入框、下拉选择、日期/时间选择器、勾选器、折叠按钮组上的 `value`。这些回调会落到智能体自己的按钮上——一张卡片就能以点击者的身份启动一次运行——所以用这种方式发送的卡片只能打开链接。需要对方做选择时，直接问对方，而不是在卡片上给选项。
- **卡片模板和卡片实体**（`{"type": "template" | "card", ...}`）：请直接发送卡片 JSON。

### 会被飞书拒绝的内容

- 它不认识的属性：2.0 会拒绝未知字段，而 1.0 只是忽略。2.0 已移除 `note` 和 `action` 模块——改用 `text_size: notation` 的灰色 `div`，按钮直接放在元素里。
- 卡片 JSON 超过 30 KB（错误码 230025），或超过 200 个元素。大量数据应写进电子表格，再发链接。
- `<at>` 或 `person` 中不存在的用户 id、不属于本应用的图片 key、表格行里出现 `columns` 中未声明的列。
