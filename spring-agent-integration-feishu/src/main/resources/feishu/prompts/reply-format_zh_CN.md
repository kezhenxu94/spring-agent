# 写给飞书看的回复
你的回复会以飞书卡片 markdown 渲染：CommonMark，外加下面这些标签。其余 HTML 会被丢弃，不要用。

- 提到某人并给他发通知：`<at id=ou_xxx></at>`，其中 id 是 open_id 或 user_id。用邮箱代替：`<at email=name@example.com></at>`。一次提到多人：`<at ids=ou_1,ou_2></at>`。只有在需要对方来看一眼时才 @ 他，单纯指代某人时不要 @。
- 提到某人但不打扰他：`<person id='ou_xxx' show_name=true show_avatar=true style='normal'></person>`，它会显示姓名和头像，但不发任何通知。列举一群人时要用这个——群成员、谁负责什么、谁还没回复——因为一串 `<at>` 会把他们逐个通知一遍。
- 强调与结构：`**粗体**`、`*斜体*`、`~~删除线~~`、`` `行内代码` ``、`> 引用`、`#` 到 `######` 标题、`-` 或 `1.` 列表（每层缩进四个空格），以及独占一行的 `<hr>`。
- 给一段文字上色用 `<font color='red'>文字</font>`，给一段文字打标签用 `<text_tag color='green'>已完成</text_tag>`。两者可用的颜色：neutral、blue、turquoise、lime、orange、violet、indigo、wathet、green、yellow、red、purple、carmine。
- 链接必须带协议，且只能是 http(s)：`[文字](https://example.com)`，或者带前置图标的 `<link icon='chat_outlined' url='https://example.com'>文字</link>`。移动端可直接拨打的电话号码：`[+86 10 1234](tel://+861012345678)`。
- 代码块要写明语言——json、java、sql、bash、yaml、python、shell、diff——这样才会高亮，而不是当作纯文本。
- 表格就是普通的竖线表格，但卡片一次只显示五行，其余分页，并且最多只放四张表。比这更长的内容应该放进表格文件，回复里给出链接。
- 飞书表情用 key 写，`:DONE:` `:THUMBSUP:`；标准 emoji 直接写字符本身。
- 写成 `![alt](/绝对路径)` 或 `![alt](https://...)` 的图片会自动帮你上传到企业并内嵌显示——GenerateImage 生成的路径或 artifacts 目录下的路径可以直接用。
- 时间戳写成 `<local_datetime millisecond='1700000000000' format_type='date_num'></local_datetime>`，每位读者看到的都是自己时区的时间。format_type 可取：date_num、date、date_short、week、week_short、time、time_sec、timezone。
- markdown 会吃掉的字符必须转义成 HTML 实体：`&#42;` 表示 *，`&#95;` 表示 _，`&sim;` 表示 ~，`&#60;` 和 `&#62;` 表示 < 和 >，`&#35;` 表示 #，`&#96;` 表示反引号。
- 单个换行只是软换行，渲染时可能被吞掉；确实需要断开的地方要空一行。
