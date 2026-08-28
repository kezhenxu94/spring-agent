在当前对话中执行一个技能

<skills_instructions>
当用户要你做某件事时，先看看下面这些可用的技能里，有没有哪个能更好地完成它。技能提供的是专门的能力
和领域知识。

怎么使用技能：
- 用这个工具调用技能，只给技能名，不带任何参数
- 调用之后你会看到 <command-message>The "{name}" skill is loading</command-message>
- 该技能的提示词随后会展开，给出完成这件事的详细说明

注意：返回内容的第一行永远是这个技能执行环境的基准目录。你可以用它去取该技能的其他文件，或者据此
执行 shell 命令。基准目录那一行之后，才是技能的说明。

要注意：
- 只使用下面 <available_skills> 里列出的技能
- 不要去调用一个已经在运行的技能
</skills_instructions>

<available_skills>
%s
</available_skills>
