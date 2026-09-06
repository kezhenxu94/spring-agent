执行 bash 命令，用于 npm、docker、make、mvn、python 这类终端操作。
**不要**用它做文件操作——请改用专门的工具：
- 查找文件：用 Glob（不要用 find 或 ls）
- 搜索内容：用 Grep（不要用 grep 或 rg）
- 读取文件：用 Read（不要用 cat/head/tail）
- 编辑文件：用 Edit（不要用 sed/awk）
- 写入文件：用 Write（不要用 echo >/cat <<EOF）

使用说明：
- command 参数是必填的。
- timeout 可选，单位毫秒（最大 600000 毫秒 / 10 分钟）。默认 120000 毫秒（2 分钟）。
- 输出在 30000 字符处截断。
- 长时间运行的命令请用 run_in_background。
- 含空格的文件路径要用双引号括起来。
- 有依赖关系的命令用 && 串联。如果前面失败也无所谓，用 ;。
- 优先使用绝对路径，而不是 cd。

重要提示：
- 除了 git 相关的 bash 命令之外，绝不要为了读代码或探索代码而额外执行命令
- 绝不要使用 TodoWrite 或 Task 工具
- 除非用户明确要求，否则不要推送到远端仓库
- 重要：绝不要使用带 -i 标志的 git 命令（比如 git rebase -i 或 git add -i），它们需要交互式输入，而这里不支持
- 如果没有任何要提交的改动（既没有未跟踪的文件，也没有修改），不要创建空提交
- 为了保证格式正确，**始终**用 HEREDOC 传递提交信息，例如：
<example>
git commit -m "$(cat <<'EOF'
提交信息写在这里。
EOF
)"
</example>

# 创建 pull request
所有与 GitHub 有关的事情——处理 issue、pull request、检查项和发布——都通过 Bash 工具调用 gh 命令完成。如果拿到一个 GitHub 链接，也用 gh 命令去取所需的信息。

重要：当用户让你创建 pull request 时，请严格按以下步骤来：

1. 你可以在一次回复里调用多个工具。当需要多份互不相关的信息、且这些命令大概率都会成功时，并行调用多个工具以获得最佳性能。请用 Bash 工具并行执行下列命令，以弄清当前分支自从与主干分叉以来的状态：
- 执行 git status，看到所有未跟踪的文件
- 执行 git diff，看到将要提交的已暂存和未暂存改动
- 检查当前分支是否跟踪了远端分支、是否与远端同步，从而知道是否需要推送
- 执行 git log 和 `git diff [base-branch]...HEAD`，弄清当前分支的完整提交历史（从与基础分支分叉那一刻起）
2. 分析将被包含进这个 pull request 的全部改动，务必查看所有相关提交（不只是最后一个提交，而是所有会被包含进来的提交！），然后起草 pull request 的摘要
3. 你可以在一次回复里调用多个工具。当需要多份互不相关的信息、且这些命令大概率都会成功时，并行调用多个工具以获得最佳性能。请并行执行下列命令：
- 如有需要，创建新分支
- 如有需要，用 -u 标志推送到远端
- 用下面的格式、通过 gh pr create 创建 PR。用 HEREDOC 传递正文，以保证格式正确。
<example>
gh pr create --title "PR 的标题" --body "$(cat <<'EOF'

## Summary
<1 到 3 条要点>

## Test plan
[用 markdown 复选框列出测试这个 pull request 要做的事……]
EOF
)"
</example>

注意：
- 不要使用 TodoWrite 或 Task 工具
- 做完之后把 PR 链接返回给用户，让他能看到

# 其他常见操作
- 查看某个 GitHub PR 上的评论：gh api repos/foo/bar/pulls/123/comments
