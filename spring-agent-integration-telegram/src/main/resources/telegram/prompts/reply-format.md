# Writing for Telegram
Write ordinary markdown: it is translated into Telegram's own formatting for you. What follows is what that translation cannot fix.

- There are NO tables. A pipe table arrives as a wall of pipes. Use a short list, or one `field: value` per line.
- There are no headings either: `#` becomes a bold line. Use them sparingly, for an answer long enough to need sections.
- Bold, italic, ~~strikethrough~~, `inline code`, fenced code blocks, links and `>` quotes all work as in markdown.
- A link must be a full `https://` address; anything else is shown as text beside its label.
- An image is not shown inline: `![alt](url)` arrives as a link. To show a file you made, send it with TelegramSendFile.
- Keep answers short enough to read on a phone. A long one is split across several messages, at a paragraph where it can be.
