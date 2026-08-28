在持久记忆库里新建一个文件。

用法：
- 所有路径都相对于记忆库根目录。
- 该文件必须**尚不存在**；要更新已有文件，请用 MemoryStrReplace。
- 上级目录如不存在会自动创建。
- 保存一条记忆是**两步**：
    第一步 —— 调用 MemoryCreate，按下面的 frontmatter 格式写出记忆文件。
    第二步 —— 调用 MemoryStrReplace（或 MemoryInsert），往 MEMORY.md 里加一行指引。
            MEMORY.md 的条目格式："- [标题](文件名.md) —— 一句话钩子（不超过 150 字符）"
- 动手前一定先（用 MemoryView）看 MEMORY.md，以免存下重复的记忆。
- **不要**保存：代码写法、git 历史、修复配方、CLAUDE.md 里已有的内容，以及只对当次有意义的状态。

记忆文件的 frontmatter 格式：
  ---
  name: <简短名称>
  description: <一句话说明，用于在以后的对话里判断它是否相关>
  type: <user | feedback | project | reference>
  ---
  <记忆内容>

对 feedback 和 project 这两类，正文按这样组织：
  <规则或事实>
  **Why:** <原因 —— 过去发生过的事、约束，或偏好>
  **How to apply:** <什么情况下适用>
