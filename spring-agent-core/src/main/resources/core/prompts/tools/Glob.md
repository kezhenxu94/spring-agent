Finds files by their name, and answers with the absolute path of each one, most recently modified
first.

`pattern` is a glob: `**/*.md` for every Markdown file at any depth, `SKILL.md` for that name
wherever it occurs, `skills/*/SKILL.md` for one level down. A pattern with no `**` is matched at any
depth anyway, so `*.java` finds a Java file in a subdirectory too.

The search is confined to the home directories this conversation reaches — your own, and the group's
and the tenant's where it has them, all of which are named in your instructions. Omitting `path`
searches your own home. A path outside those directories is refused rather than searched, and that
is the whole answer: there is nothing else on this machine you can look at this way.

`.git`, `node_modules`, `target`, `build`, `dist`, `.idea`, `.vscode` and `__pycache__` are skipped.

Use this when you know what a file is called, or what it must be called, but not where it is. Use
Grep when you know what is inside it instead. Then Read the path this gives you — a path is not
content, and nothing here tells you what a file says.

Several calls in one reply are fine, and are the cheap way to search: guess at two or three patterns
at once rather than narrowing one of them over three turns.
