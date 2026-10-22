以飞书支持的任意格式发送消息：卡片（msgType 为 interactive），可包含标题栏、分栏、表格、图表、图片、人员、折叠面板和链接按钮；富文本（post）；纯文本；图片；群名片（share_chat）或个人名片（share_user）。编写前先读 FeishuMessageFormatReference。只发普通 markdown 时，用 FeishuSendMessage 更简单。
图片：在填 img_key 或 image_key 的地方写工作区内文件的绝对路径，它会被自动上传。卡片只能打开链接：表单、输入框、下拉选择以及其他任何会回调的交互都会被拒绝。飞书拒收内容时，结果中会带上它的错误码、错误信息以及它指出的字段；按此改正后再发一次。
