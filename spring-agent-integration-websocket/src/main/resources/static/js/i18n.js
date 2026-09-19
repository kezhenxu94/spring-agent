// What the page says for itself, as opposed to what the model wrote or what the server sent.
//
// The server has a bundle of its own (web/messages*.properties) for the reasons it refuses a
// request; this one is for labels and buttons, which the server never sees. Same keys, same
// languages, so adding a language means one file on each side and nothing else.
//
// Which language is chosen is settled by the server, not here: the page starts in the locale
// /api/me reported, which the server resolved from the SPRING_AGENT_LOCALE cookie or, failing that,
// from Accept-Language. Deciding it a second time in JavaScript would let the two disagree, and a
// page whose buttons and whose error messages are in different languages is worse than either.
//
// The vocabulary is deliberate: you *attach* to a run rather than open a chat, because a run lives
// on the server and this page is only watching it. Every label that touches that idea uses the same
// word, so the interface teaches the model it is built on.

const STRINGS = {
  en: {
    'app.title': 'Spring Agent',

    'nav.new': 'New conversation',
    'nav.conversations': 'Conversations',
    'nav.tasks': 'Scheduled',
    'nav.untitled': 'Untitled',
    'chat.rename': 'Rename this conversation',
    'nav.empty': 'Nothing here yet.',
    'nav.signout': 'Sign out',
    'nav.rename': 'Rename',
    'nav.delete': 'Delete conversation',
    'nav.actions': 'What can be done with this conversation',
    'nav.menu': 'Conversations',
    'nav.close': 'Close',
    'nav.fold': 'Fold the sidebar',
    'nav.unfold': 'Unfold the sidebar',

    'empty.title': 'Ask the agent to do something.',
    'empty.body': 'It works on the server, so you can close this tab and come back. '
      + 'Whatever it is doing will still be here.',

    'composer.placeholder': 'Ask anything. Shift+Enter for a new line.',
    'composer.placeholder.running': 'Add something and it joins the run…',
    'composer.send': 'Send',
    'composer.tools': 'Add to this message',
    'composer.attach': 'Attach files',
    'composer.attach.remove': 'Remove from this message',
    'composer.attach.done': 'Uploaded {0} file(s) to your artifacts.',
    'composer.attach.note': 'I have put these in my artifacts directory: {0}',
    'composer.stop': 'Stop run',
    'composer.scroll.end': 'Jump to the latest',
    // The chat toggle. Two titles rather than one, because the button's own state is the thing
    // worth saying: a title reading "Also send to Feishu" on a button that is already doing so
    // leaves the reader guessing which way round it is.
    'composer.mirror': 'Also send to the chat',
    'composer.mirror.on': 'Answers also go to {0}. Click to stop.',
    'composer.mirror.off': 'Also send the answer to {0}',
    'composer.mirror.surface.feishu': 'Feishu',

    'run.thinking': 'Thinking',
    'run.thinking.gone': 'What this round thought is no longer kept.',
    'run.tools': 'Tool calls',
    'run.subagent': 'Subagent',
    'run.subagent.running': 'working',
    'run.subagent.done': 'done',
    'run.subagent.stopped': 'stopped',
    'run.subagent.failed': 'failed',
    'run.todo': 'To do',
    'run.sources': 'Sources',
    'run.usage': '{0} · {1} tokens',
    'run.queued': 'Queued: {0}',
    'run.queued.read': 'Picked up',
    'run.queued.sent': 'Added to the run in progress.',
    'run.mirror.next': 'This run is already going. The answer after it is the first one sent on.',
    'run.stopped': 'Stopped.',
    'run.failed': 'The run hit a problem',
    'run.reattached': 'Reattached.',

    'question.title': 'The agent needs an answer',
    'question.other': 'Something else…',
    'question.submit': 'Answer',
    'question.sending': 'Sending…',

    'tasks.title': 'Scheduled',
    'tasks.subtitle': 'What the agent has been asked to do later. A scheduled task runs on the '
      + 'server, whether or not this page is open, and writes its answer into the conversation it '
      + 'came from. Ask for one in a conversation — there is nothing to fill in here.',
    'task.none': 'No scheduled work.',
    'task.schedule': 'Schedule',
    'task.runs.label': 'Runs',
    'task.background.label': 'Unattended',
    'task.background.yes': 'Runs without waiting for anyone',
    'task.runs': '{0}/{1} runs',
    'task.cancel': 'Cancel this task',
    'task.cancel.title': 'Cancel this scheduled task?',
    'task.cancel.confirm': 'It will not run again. The conversation it writes into stays.',
    'task.cancel.action': 'Cancel it',
    'task.cancelled': 'Scheduled task cancelled.',
    'task.actions': 'What can be done with this task',
    'task.open': 'Open its conversation',
    'task.kind': 'Scheduled task',
    'task.next.unknown': 'Next run not worked out yet',
    'task.repeats': 'Repeats',
    'task.once': 'Once',
    'task.what': 'What it will do',
    'task.title': 'Name',
    'task.next': 'Next run',
    'task.edit': 'Edit this task',
    'task.edit.maxruns': 'Total firings',
    'task.edit.kind': 'How often',
    'task.edit.cron.hint': 'Six fields: second, minute, hour, day of month, month, day of week.',
    'task.edit.when': 'Fires at',
    'task.edit.expires': 'Expires at',
    'task.edit.expires.never': 'Never expires',
    'task.edit.runs.hint': 'Leave empty to fire until it expires or is cancelled.',
    'task.edit.nothing': 'Nothing changed.',
    'task.edit.save': 'Save changes',
    'task.edit.saving': 'Saving…',
    'task.edit.saved': 'Saved.',
    'task.edit.left': '{0} characters left',

    'delete.title': 'Delete this conversation?',
    'delete.confirm': 'What was said in it goes too. This cannot be undone.',
    'delete.action': 'Delete it',
    'delete.done': 'Conversation deleted.',

    'busy.loading': 'Loading…',
    'confirm.cancel': 'Keep it',
    'confirm.working': 'Working…',
    'confirm.ok': 'Go ahead',

    'nav.account': 'Account',
    'identity.copy': 'Copy user id',
    'identity.copied': 'User id copied.',
    'identity.copy.failed': 'This browser will not let the page copy. The id is in the tooltip.',

    'theme': 'Theme',
    'theme.auto': 'Match system',
    'theme.light': 'Light',
    'theme.dark': 'Dark',
    'language': 'Language',

    'denied.label': 'No access',
    'denied.title': 'This deployment does not serve your tenant.',
    'denied.body': 'You signed in, but your Feishu tenant is not the one this server was '
      + 'configured for. Whoever runs it needs to set app.web.auth.tenant-id (FEISHU_TENANT_ID) '
      + 'to the tenant below.',
    'denied.you': 'Signed in as',
    'denied.tenant': 'Your tenant',
    'denied.signout': 'Sign out',
    'denied.short': 'Your tenant is not served by this deployment.',

    'nav.customize': 'Customize',

    // Customize, and the skills in it. A skill is a folder of instructions the agent loads by
    // name, so the words here are about folders and files rather than about documents.
    'customize.title': 'Customize',
    'customize.subtitle': 'What this agent has been taught, as against what it has been told. '
      + 'Everything here is also reachable from a chat, by asking for it.',
    'customize.sections': 'What Customize holds',
    'customize.skills': 'Skills',
    'customize.memories': 'Memories',
    'customize.mcp': 'MCP servers',

    // Memories: what the agent concluded about somebody, as against what they taught it. The
    // vocabulary is deliberately not the skills tab's — a skill is written, a memory is *learnt* —
    // because the difference is the whole reason a person would want to read these.
    'memories.scopes': 'Whose memories to show',
    'memories.scope.own': 'Yours',
    'memories.scope.tenant': 'Company',
    'memories.search': 'Search memories…',
    'memories.search.open': 'Search memories',
    'memories.search.close': 'Show every memory',
    'memories.new': 'New memory',
    'memories.kind': 'Memory',
    'memories.index': 'index',
    'memories.actions': 'What can be done with this memory',
    'memories.none': 'Nothing remembered yet. The agent writes a memory when it learns something '
      + 'about how you work that is worth keeping between conversations.',
    'memories.none.company': 'Nothing is remembered company-wide yet.',
    'memories.none.search': 'No memory matched that.',
    'memories.back': 'All memories',
    'memories.path': 'File',
    'memories.type': 'Kind',
    'memories.description': 'What it claims',
    'memories.updated': 'Last written',
    'memories.body': 'The memory itself',
    // The four kinds the memory prompt defines. A memory written with a fifth is shown with
    // whatever word it used rather than dropped — see memories-list.js.
    'memories.type.user': 'about you',
    'memories.type.feedback': 'how to work',
    'memories.type.project': 'ongoing work',
    'memories.type.reference': 'where to look',
    'memories.loading': 'Opening…',
    'memories.gone': 'No memory at {0} here. It may have been rewritten or deleted.',
    'memories.binary': 'This file is not text, so there is nothing to show here.',
    'memories.large': 'This memory is too large to show. It is {0} — open it from a chat instead.',
    'memories.empty': 'This memory is empty.',
    'memories.edit': 'Edit',
    'memories.save': 'Save',
    'memories.cancel': 'Cancel',
    'memories.saved': 'Saved {0}.',
    'memories.delete': 'Forget this',
    'memories.delete.confirm': 'Forget {0}?',
    'memories.delete.body': 'The agent stops knowing this. Nothing keeps a copy, and the index '
      + 'still mentions it until somebody edits MEMORY.md.',
    'memories.deleted': 'Forgot {0}.',
    'memories.new.title': 'Name the memory',
    'memories.new.body': 'The name is the file it goes in, ending in .md. It opens with the front '
      + 'matter already in it — the description is what the agent reads to judge whether the '
      + 'memory is relevant, so it is worth writing.',
    'memories.new.name': 'web-ui-compactness-wins.md',
    'memories.new.create': 'Create',

    // MCP servers. Everything here is about a connection somebody configured, so the words are the
    // ones an operator would use — endpoint, header, prefix — rather than softened versions of
    // them: whoever is on this page has a URL and a token in front of them.
    'mcp.intro': 'Servers the agent can reach for tools beyond the ones built in. Remote '
      + 'streamable HTTP only — a server is connected to and asked what it offers before it is '
      + 'saved.',
    'mcp.new': 'Add a server',
    'mcp.new.title': 'New server',
    'mcp.kind': 'MCP server',
    'mcp.mine': 'Yours',
    'mcp.scopes': 'Whose servers to show',
    'mcp.group.mine': 'Yours',
    'mcp.group.shared': 'Shared with you',
    'mcp.group.configured': 'Configured here',
    'mcp.none.mine': 'You have not added a server yet.',
    'mcp.none.shared': 'Nobody has shared a server with you.',
    'mcp.none.configured': 'This deployment configures no servers of its own.',
    'mcp.actions': 'What can be done with this server',
    'mcp.back': 'All servers',
    'mcp.loading': 'Opening…',
    'mcp.by': 'shared by {0}',
    'mcp.off': 'off',
    'mcp.prefix.is': 'tools: {0}_…',
    'mcp.prefix.derived': 'Nobody chose a prefix, so it is derived from the name. Set one and the '
      + 'tools get a name you can recognise in a transcript.',
    'mcp.authenticated': 'sends {0}',
    'mcp.shared.everyone': 'shared with everyone',
    'mcp.shared.count': 'shared with {0}',
    'mcp.enable': 'Turn on',
    'mcp.disable': 'Turn off',
    'mcp.enabled': 'Turned {0} on.',
    'mcp.disabled': 'Turned {0} off. It is still here, with its URL and credential.',
    'mcp.delete': 'Remove this server',
    'mcp.delete.confirm': 'Remove {0}?',
    'mcp.delete.body': 'Its URL, its credential and everyone it was shared with go with it. The '
      + 'agent stops being able to call its tools. This cannot be undone.',
    'mcp.deleted': 'Removed {0}.',
    'mcp.field.name': 'Name',
    'mcp.field.name.hint': 'What you will call it. It is also how the agent refers to it.',
    'mcp.field.name.fixed': 'The name identifies the server and cannot be changed here. Add it '
      + 'again under a new name and remove this one.',
    'mcp.field.url': 'Endpoint',
    'mcp.field.url.hint': 'The streamable HTTP endpoint. Private and loopback addresses are '
      + 'refused.',
    'mcp.field.auth': 'Authentication',
    'mcp.field.auth.hint': 'One header, as Name: value. Left empty, nothing is sent.',
    'mcp.field.auth.set': 'Currently sends {0}. Type to replace it; leave this empty and it stays '
      + 'as it is.',
    'mcp.field.auth.clear': 'Send nothing',
    'mcp.field.auth.cleared': 'Saving will stop sending any header.',
    'mcp.field.prefix': 'Tool prefix',
    'mcp.field.prefix.hint': 'What every tool of this server is named after — github makes '
      + 'github_search_issues. Left empty it is derived from the name, which works but tells you '
      + 'nothing when you meet the tool.',
    'mcp.field.description': 'Note',
    'mcp.register': 'Connect and save',
    'mcp.save': 'Save',
    'mcp.connecting': 'Connecting to the server…',
    'mcp.saved': 'Saved {0}.',
    'mcp.saved.tools': 'Saved {0}. It offers {1} tool(s).',
    'mcp.sharing': 'Shared with',
    'mcp.sharing.hint': 'Anyone here can use this server\u2019s tools. They cannot see its URL, its '
      + 'credential or this list, and they cannot change or share it.',
    'mcp.sharing.none': 'Nobody yet.',
    'mcp.share': 'Share',
    'mcp.share.placeholder': 'open_id or chat_id',
    'mcp.shared': 'Shared {0} with {1}.',
    'mcp.unshare': 'Stop sharing with this one',
    'mcp.unshared': 'Stopped sharing {0}.',
    'mcp.share.everyone': 'everyone',
    'mcp.share.everyone.do': 'Share with everyone',
    'mcp.share.everyone.confirm': 'Share {0} with everyone?',
    'mcp.share.everyone.body': 'Every account this deployment serves gets to use this server\u2019s '
      + 'tools, through your credential. Nothing afterwards says how many people that is.',

    'skills.scopes': 'Whose skills to show',
    'skills.scope.own': 'Yours',
    'skills.scope.tenant': 'Company',
    'skills.search': 'Search skills…',
    'skills.search.open': 'Search skills',
    'skills.search.close': 'Show every skill',
    'skills.new': 'New skill',
    'skills.new.upload': 'Upload a skill',
    'skills.new.blank': 'Create a skill',
    'skills.kind': 'Skill',
    'skills.none': 'No skills yet. The agent writes one when you ask it to remember a method, '
      + 'or you can start one here.',
    'skills.none.company': 'Nobody has shared a skill with the company yet.',
    'skills.none.search': 'No skill matched that.',
    'skills.files': '{0} files',
    'skills.file.one': 'one file',
    'skills.updated': 'updated {0}',
    'skills.unnamed': 'no name in SKILL.md',
    'skills.unnamed.why': 'A skill with no name in its front matter is never offered to the '
      + 'agent. Add a name: line to SKILL.md.',
    'skills.shadowed': 'shadowed',
    'skills.shadowed.why': 'You have a skill of this name of your own, and the agent loads that '
      + 'one. Rename either to use both.',
    'skills.back': 'All skills',
    'skills.tree': 'Files in this skill',
    'skills.tree.show': 'Files',
    'skills.resize': 'Resize the file list',
    'skills.file.none': 'Choose a file to read it.',
    'skills.file.loading': 'Opening…',
    'skills.loading': 'Opening the skill…',
    'skills.gone': 'No skill called {0} here. It may have been renamed or deleted.',
    'skills.file.binary': 'This file is not text, so there is nothing to show here.',
    'skills.file.large': 'This file is too large to show. It is {0} — open it from a chat instead.',
    'skills.file.empty': 'This file is empty.',
    'skills.file.declared': 'This file says only the name and description above it.',
    'skills.edit': 'Edit',
    'skills.save': 'Save',
    'skills.cancel': 'Cancel',
    'skills.saved': 'Saved {0}.',
    'skills.upload': 'Add files',
    'skills.download': 'Download as .zip',
    'skills.uploading': 'Adding {0} file(s)…',
    'skills.uploaded': 'Added {0} file(s).',
    'skills.actions': 'What can be done with this skill',
    'skills.delete': 'Delete this skill',
    'skills.delete.confirm': 'Delete the skill {0}?',
    'skills.delete.body': 'Every file in it goes, and the agent stops being able to load it. '
      + 'This cannot be undone.',
    'skills.deleted': 'Deleted {0}.',
    'skills.file.delete': 'Delete this file',
    'skills.file.delete.confirm': 'Delete {0}?',
    'skills.file.delete.body': 'The rest of the skill stays. This cannot be undone.',
    'skills.file.deleted': 'Deleted {0}.',
    'skills.new.title': 'Name the skill',
    'skills.new.body': 'The name is the folder its files go in. The agent loads it by the name '
      + 'inside SKILL.md, which starts out the same.',
    'skills.new.name': 'Name',
    'skills.new.description': 'What it does, and when to use it',
    'skills.new.zip': 'Upload a .zip',
    'skills.new.create': 'Create it',
    'skills.created': 'Created {0}.',
    'skills.imported': 'Imported {0}.',

    'nav.knowledge': 'Knowledge base',
    'nav.sections': 'What the sidebar shows',

    'knowledge.title': 'Knowledge base',
    'knowledge.kind': 'Document',
    'knowledge.add': 'Add to the knowledge base',
    'knowledge.source': 'From',
    'knowledge.chunks.label': 'Chunks',
    'knowledge.added': 'Added',
    'knowledge.score': 'Match',
    'knowledge.subtitle': 'What the agent has been told to remember. It is searched on every '
      + 'message, and everything here can also be reached with the knowledge tools in a chat.',
    'knowledge.search': 'Search the knowledge base',
    'knowledge.search.close': 'Show the whole list',
    'knowledge.search.placeholder': 'Search what is stored…',
    'knowledge.results': '{0} document(s) matched.',
    'knowledge.none': 'Nothing stored yet.',
    'knowledge.none.search': 'Nothing matched that.',
    'knowledge.more': 'Load more',
    'knowledge.scope.own': 'Only you',
    'knowledge.scope.group': 'Group',
    'knowledge.scope.tenant': 'Company',
    'knowledge.upload': 'Add files',
    'knowledge.upload.hint': 'Files are stored in your artifacts and indexed straight away.',
    'knowledge.uploading': 'Indexing {0} file(s)…',
    'knowledge.uploaded': 'Indexed {0} document(s).',
    'knowledge.note': 'Write a note',
    'knowledge.note.title': 'Title',
    'knowledge.note.text': 'What should the agent remember?',
    'knowledge.note.save': 'Store it',
    'knowledge.note.saved': 'Stored.',
    'knowledge.cancel': 'Cancel',
    'knowledge.into': 'Into',
    'knowledge.delete': 'Delete',
    'knowledge.delete.title': 'Delete this document?',
    'knowledge.delete.confirm': 'The agent stops being able to recall it. This cannot be undone.',
    'knowledge.delete.confirm.owner': 'It is stored under {0}, not you. The agent stops being able to recall it. This cannot be undone.',
    'knowledge.delete.action': 'Delete it',
    'knowledge.actions': 'What can be done with this document',
    'knowledge.content': 'What is stored',
    'knowledge.content.empty': 'This document holds no text.',
    'knowledge.deleted': 'Deleted.',
    'knowledge.share': 'Share with the company',
    'knowledge.unshare': 'Keep to yourself',
    'knowledge.moving': 'Moving it…',
    'knowledge.moved': 'Moved.',
    'knowledge.view': 'What this list shows',
    'knowledge.filter.all': 'Everything stored',
    'knowledge.filter.showing': 'Showing {0} only.',
    'knowledge.owner': 'Read another person',
    'knowledge.owner.placeholder': 'Their user id',
    'knowledge.owner.browse': 'Open',
    'knowledge.owner.mine': 'Back to mine',
    'knowledge.owner.reading': 'Reading {0} — read only.',
    'knowledge.owner.triage': 'Event triage · {0}',

    'toast.dismiss': 'Dismiss',
    'error.generic': 'That did not work.',
    'error.forbidden': 'Refused. Reload the page and try again.',
    'error.offline': 'The server is not answering.',
    'error.render': 'Could not draw part of the run.',
  },

  zh: {
    'app.title': 'Spring 智能体',

    'nav.new': '新建对话',
    'nav.conversations': '对话',
    'nav.tasks': '定时任务',
    'nav.untitled': '未命名',
    'chat.rename': '重命名这个对话',
    'nav.empty': '还没有内容。',
    'nav.signout': '退出登录',
    'nav.rename': '重命名',
    'nav.delete': '删除对话',
    'nav.actions': '这个对话可以做的操作',
    'nav.menu': '对话列表',
    'nav.close': '关闭',
    'nav.fold': '收起侧栏',
    'nav.unfold': '展开侧栏',

    'empty.title': '交给智能体一件事。',
    'empty.body': '它在服务端执行，可以随时关掉这个页面。回来时正在做的事仍然在。',

    'composer.placeholder': '随便问点什么，Shift+Enter 换行。',
    'composer.placeholder.running': '继续输入会插入到当前这轮…',
    'composer.send': '发送',
    'composer.tools': '为这条消息添加内容',
    'composer.attach': '上传文件',
    'composer.attach.remove': '不在这条消息中引用',
    'composer.attach.done': '已上传 {0} 个文件到你的 artifacts 目录。',
    'composer.attach.note': '我已经把这些文件放到 artifacts 目录：{0}',
    'composer.stop': '停止执行',
    'composer.scroll.end': '回到最新消息',
    'composer.mirror': '同时发送到聊天',
    'composer.mirror.on': '回答会同时发送到{0}，点击可关闭。',
    'composer.mirror.off': '把回答同时发送到{0}',
    'composer.mirror.surface.feishu': '飞书',

    'run.thinking': '思考过程',
    'run.thinking.gone': '这一轮的思考过程已不再保留。',
    'run.tools': '工具调用',
    'run.subagent': '子智能体',
    'run.subagent.running': '执行中',
    'run.subagent.done': '完成',
    'run.subagent.stopped': '已停止',
    'run.subagent.failed': '失败',
    'run.todo': '待办',
    'run.sources': '参考来源',
    'run.usage': '{0} · {1} tokens',
    'run.queued': '已排队：{0}',
    'run.queued.read': '已读取',
    'run.queued.sent': '已插入到正在执行的这一轮。',
    'run.mirror.next': '这一轮已经在执行了，从下一个回答开始同步。',
    'run.stopped': '已停止。',
    'run.failed': '执行出错',
    'run.reattached': '已重新连接。',

    'question.title': '智能体需要你的回答',
    'question.other': '其他…',
    'question.submit': '提交回答',
    'question.sending': '提交中…',

    'tasks.title': '定时任务',
    'tasks.subtitle': '智能体被要求稍后去做的事。定时任务在服务器上运行，无论这个页面是否打开，'
      + '并把结果写回它所属的对话。需要新建时，在对话中直接告诉智能体即可，这里没有要填的表单。',
    'task.none': '没有定时任务。',
    'task.schedule': '执行计划',
    'task.runs.label': '已执行',
    'task.background.label': '无人值守',
    'task.background.yes': '无需等待任何人即可运行',
    'task.runs': '{0}/{1} 次',
    'task.cancel': '取消这个任务',
    'task.cancel.title': '取消这个定时任务？',
    'task.cancel.confirm': '它将不再执行。它写入的那个对话会保留。',
    'task.cancel.action': '取消任务',
    'task.cancelled': '定时任务已取消。',
    'task.actions': '这个任务可以做的操作',
    'task.open': '打开所属对话',
    'task.kind': '定时任务',
    'task.next.unknown': '下次执行时间尚未计算',
    'task.repeats': '重复',
    'task.once': '一次',
    'task.what': '它要做的事',
    'task.title': '名称',
    'task.next': '下次执行',
    'task.edit': '修改这个任务',
    'task.edit.maxruns': '总执行次数',
    'task.edit.kind': '频率',
    'task.edit.cron.hint': '六个字段：秒、分、时、日、月、周。',
    'task.edit.when': '执行时间',
    'task.edit.expires': '到期时间',
    'task.edit.expires.never': '永不过期',
    'task.edit.runs.hint': '留空表示直到到期或被取消为止一直执行。',
    'task.edit.nothing': '没有任何改动。',
    'task.edit.save': '保存修改',
    'task.edit.saving': '保存中…',
    'task.edit.saved': '已保存。',
    'task.edit.left': '还可以输入 {0} 个字符',

    'delete.title': '删除这个对话？',
    'delete.confirm': '其中的内容也会一并删除，且无法恢复。',
    'delete.action': '删除',
    'delete.done': '对话已删除。',

    'busy.loading': '加载中…',
    'confirm.cancel': '再想想',
    'confirm.working': '处理中…',
    'confirm.ok': '确定',

    'nav.account': '账户',
    'identity.copy': '复制用户 ID',
    'identity.copied': '用户 ID 已复制。',
    'identity.copy.failed': '当前浏览器不允许页面复制，ID 在悬浮提示中。',

    'theme': '主题',
    'theme.auto': '跟随系统',
    'theme.light': '浅色',
    'theme.dark': '深色',
    'language': '语言',

    'denied.label': '无权访问',
    'denied.title': '这个部署不服务你所在的租户。',
    'denied.body': '你已登录，但你的飞书租户不是这台服务器配置的那个。'
      + '需要管理员把 app.web.auth.tenant-id（FEISHU_TENANT_ID）设置为下面这个租户。',
    'denied.you': '当前身份',
    'denied.tenant': '你的租户',
    'denied.signout': '退出登录',
    'denied.short': '这个部署不服务你所在的租户。',

    'nav.customize': '自定义',

    'customize.title': '自定义',
    'customize.subtitle': '这里是智能体学会的东西，而不是别人告诉它的东西。这些内容在对话中也可以直接让它处理。',
    'customize.sections': '自定义包含的内容',
    'customize.skills': '技能',
    'customize.memories': '记忆',
    'customize.mcp': 'MCP 服务器',

    // 记忆：智能体自己总结出来的东西，而不是别人教给它的。用词刻意与技能区分开——
    // 技能是写出来的，记忆是学到的，而这个区别正是有人想来看这些内容的理由。
    'memories.scopes': '查看谁的记忆',
    'memories.scope.own': '你的',
    'memories.scope.tenant': '公司',
    'memories.search': '搜索记忆…',
    'memories.search.open': '搜索记忆',
    'memories.search.close': '显示全部记忆',
    'memories.new': '新建记忆',
    'memories.kind': '记忆',
    'memories.index': '索引',
    'memories.actions': '可以对这条记忆做什么',
    'memories.none': '还没有记住什么。当智能体了解到关于你的工作方式、值得跨对话保留的内容时，'
      + '它会写下一条记忆。',
    'memories.none.company': '公司范围内还没有记住任何内容。',
    'memories.none.search': '没有匹配的记忆。',
    'memories.back': '全部记忆',
    'memories.path': '文件',
    'memories.type': '类型',
    'memories.description': '它说的是什么',
    'memories.updated': '最后写入',
    'memories.body': '记忆正文',
    'memories.type.user': '关于你',
    'memories.type.feedback': '怎么做事',
    'memories.type.project': '进行中的工作',
    'memories.type.reference': '去哪里找',
    'memories.loading': '正在打开…',
    'memories.gone': '这里没有位于 {0} 的记忆，它可能已被改写或删除。',
    'memories.binary': '这个文件不是文本，没有可显示的内容。',
    'memories.large': '这条记忆太大，无法在这里显示。它有 {0}，请在对话中打开。',
    'memories.empty': '这条记忆是空的。',
    'memories.edit': '编辑',
    'memories.save': '保存',
    'memories.cancel': '取消',
    'memories.saved': '已保存 {0}。',
    'memories.delete': '忘掉它',
    'memories.delete.confirm': '忘掉 {0}？',
    'memories.delete.body': '智能体将不再知道这件事。系统不会保留副本，而且在有人编辑 MEMORY.md 之前，'
      + '索引里仍会提到它。',
    'memories.deleted': '已忘掉 {0}。',
    'memories.new.title': '为这条记忆取名',
    'memories.new.body': '名称就是它所在的文件名，以 .md 结尾。新建后会带上 front matter——'
      + '其中的描述是智能体用来判断这条记忆是否相关的依据，值得认真写。',
    'memories.new.name': 'web-ui-compactness-wins.md',
    'memories.new.create': '创建',

    // MCP 服务器。这里说的都是别人配置出来的连接，因此用词就用运维会用的那一套——
    // 接入地址、请求头、前缀——而不是把它们说得更含糊：来到这个页面的人手里就拿着 URL 和令牌。
    'mcp.intro': '智能体可以连过去取用工具的服务器，用来补充内置的那些。仅支持远程 streamable HTTP——'
      + '保存之前会真的连上去，问它提供哪些工具。',
    'mcp.new': '添加服务器',
    'mcp.new.title': '新服务器',
    'mcp.kind': 'MCP 服务器',
    'mcp.mine': '你的',
    'mcp.scopes': '查看谁的服务器',
    'mcp.group.mine': '你的',
    'mcp.group.shared': '分享给你的',
    'mcp.group.configured': '本部署配置的',
    'mcp.none.mine': '你还没有添加服务器。',
    'mcp.none.shared': '还没有人向你分享服务器。',
    'mcp.none.configured': '本部署没有配置自己的服务器。',
    'mcp.actions': '可以对这个服务器做什么',
    'mcp.back': '全部服务器',
    'mcp.loading': '正在打开…',
    'mcp.by': '由 {0} 分享',
    'mcp.off': '已关闭',
    'mcp.prefix.is': '工具：{0}_…',
    'mcp.prefix.derived': '没有人指定前缀，于是它由名称推导而来。指定一个，工具名在对话记录里就认得出来了。',
    'mcp.authenticated': '会发送 {0}',
    'mcp.shared.everyone': '已分享给所有人',
    'mcp.shared.count': '已分享给 {0} 个对象',
    'mcp.enable': '开启',
    'mcp.disable': '关闭',
    'mcp.enabled': '已开启 {0}。',
    'mcp.disabled': '已关闭 {0}。它仍在这里，URL 和凭据都还在。',
    'mcp.delete': '移除这个服务器',
    'mcp.delete.confirm': '移除 {0}？',
    'mcp.delete.body': '它的 URL、凭据，以及分享给的所有对象都会一并消失，智能体将无法再调用它的工具。'
      + '此操作无法撤销。',
    'mcp.deleted': '已移除 {0}。',
    'mcp.field.name': '名称',
    'mcp.field.name.hint': '你怎么称呼它，智能体也用这个名字指代它。',
    'mcp.field.name.fixed': '名称是这个服务器的标识，不能在这里修改。请用新名称重新添加，再移除这一个。',
    'mcp.field.url': '接入地址',
    'mcp.field.url.hint': 'streamable HTTP 的接入地址。内网地址和回环地址会被拒绝。',
    'mcp.field.auth': '认证',
    'mcp.field.auth.hint': '一个请求头，写成 名称: 值。留空则不发送任何请求头。',
    'mcp.field.auth.set': '当前会发送 {0}。想替换就直接填写；留空则保持不变。',
    'mcp.field.auth.clear': '不再发送',
    'mcp.field.auth.cleared': '保存后将不再发送任何请求头。',
    'mcp.field.prefix': '工具前缀',
    'mcp.field.prefix.hint': '这个服务器的每个工具都以它命名——填 github 就会得到 github_search_issues。'
      + '留空则由名称推导，能用，但你见到那个工具时它什么也说明不了。',
    'mcp.field.description': '备注',
    'mcp.register': '连接并保存',
    'mcp.save': '保存',
    'mcp.connecting': '正在连接服务器…',
    'mcp.saved': '已保存 {0}。',
    'mcp.saved.tools': '已保存 {0}，它提供 {1} 个工具。',
    'mcp.sharing': '已分享给',
    'mcp.sharing.hint': '这里的人都能使用这个服务器的工具。他们看不到它的 URL、凭据和这份名单，'
      + '也不能修改或再分享它。',
    'mcp.sharing.none': '还没有分享给任何人。',
    'mcp.share': '分享',
    'mcp.share.placeholder': 'open_id 或 chat_id',
    'mcp.shared': '已把 {0} 分享给 {1}。',
    'mcp.unshare': '取消对这个对象的分享',
    'mcp.unshared': '已取消分享 {0}。',
    'mcp.share.everyone': '所有人',
    'mcp.share.everyone.do': '分享给所有人',
    'mcp.share.everyone.confirm': '把 {0} 分享给所有人？',
    'mcp.share.everyone.body': '本部署服务的每一个账号都将能通过你的凭据使用这个服务器的工具，'
      + '而事后没有任何地方会告诉你那是多少人。',

    'skills.scopes': '查看谁的技能',
    'skills.scope.own': '我的',
    'skills.scope.tenant': '公司',
    'skills.search': '搜索技能……',
    'skills.search.open': '搜索技能',
    'skills.search.close': '显示全部技能',
    'skills.new': '新建技能',
    'skills.new.upload': '上传技能',
    'skills.new.blank': '新建技能',
    'skills.kind': '技能',
    'skills.none': '还没有技能。你可以让智能体把某个做法记下来，它会写一个；也可以在这里新建。',
    'skills.none.company': '还没有人把技能共享给公司。',
    'skills.none.search': '没有匹配的技能。',
    'skills.files': '{0} 个文件',
    'skills.file.one': '1 个文件',
    'skills.updated': '更新于 {0}',
    'skills.unnamed': 'SKILL.md 中没有名称',
    'skills.unnamed.why': '前置信息里没有 name 的技能不会提供给智能体。请在 SKILL.md 中补上 name 一行。',
    'skills.shadowed': '被覆盖',
    'skills.shadowed.why': '你自己也有同名技能，智能体加载的是你自己的那个。改掉其中一个的名称即可同时使用。',
    'skills.back': '全部技能',
    'skills.tree': '该技能中的文件',
    'skills.tree.show': '文件',
    'skills.resize': '调整文件列表宽度',
    'skills.file.none': '选择一个文件来查看内容。',
    'skills.file.loading': '正在打开……',
    'skills.loading': '正在打开技能……',
    'skills.gone': '这里没有名为 {0} 的技能，它可能已被重命名或删除。',
    'skills.file.binary': '这不是文本文件，没有内容可以显示。',
    'skills.file.large': '文件太大，无法在此显示（{0}）。请在对话中让智能体打开。',
    'skills.file.empty': '这个文件是空的。',
    'skills.file.declared': '这个文件只写了上面的名称和描述。',
    'skills.edit': '编辑',
    'skills.save': '保存',
    'skills.cancel': '取消',
    'skills.saved': '已保存 {0}。',
    'skills.upload': '添加文件',
    'skills.download': '下载为 .zip',
    'skills.uploading': '正在添加 {0} 个文件……',
    'skills.uploaded': '已添加 {0} 个文件。',
    'skills.actions': '这个技能可以做的操作',
    'skills.delete': '删除这个技能',
    'skills.delete.confirm': '确定删除技能 {0} 吗？',
    'skills.delete.body': '其中所有文件都会被删除，智能体也将无法再加载它。此操作无法撤销。',
    'skills.deleted': '已删除 {0}。',
    'skills.file.delete': '删除这个文件',
    'skills.file.delete.confirm': '确定删除 {0} 吗？',
    'skills.file.delete.body': '技能的其余部分会保留。此操作无法撤销。',
    'skills.file.deleted': '已删除 {0}。',
    'skills.new.title': '为技能取名',
    'skills.new.body': '名称就是存放文件的文件夹名。智能体按 SKILL.md 里的名称加载，两者一开始是一致的。',
    'skills.new.name': '名称',
    'skills.new.description': '它做什么，什么时候用',
    'skills.new.zip': '上传 .zip 压缩包',
    'skills.new.create': '创建',
    'skills.created': '已创建 {0}。',
    'skills.imported': '已导入 {0}。',

    'nav.knowledge': '知识库',
    'nav.sections': '侧栏显示的内容',

    'knowledge.title': '知识库',
    'knowledge.kind': '文档',
    'knowledge.add': '添加到知识库',
    'knowledge.source': '来源',
    'knowledge.chunks.label': '片段数',
    'knowledge.added': '添加时间',
    'knowledge.score': '匹配度',
    'knowledge.subtitle': '智能体被要求记住的内容。每次对话都会检索这里，'
      + '在聊天中也可以用知识库工具管理同样的内容。',
    'knowledge.search': '搜索知识库',
    'knowledge.search.close': '显示全部文档',
    'knowledge.search.placeholder': '搜索已存内容…',
    'knowledge.results': '匹配到 {0} 篇文档。',
    'knowledge.none': '还没有存入任何内容。',
    'knowledge.none.search': '没有匹配到内容。',
    'knowledge.more': '加载更多',
    'knowledge.scope.own': '仅自己',
    'knowledge.scope.group': '群组',
    'knowledge.scope.tenant': '公司',
    'knowledge.upload': '上传文件',
    'knowledge.upload.hint': '文件会保存到你的工作目录，并立即建立索引。',
    'knowledge.uploading': '正在索引 {0} 个文件…',
    'knowledge.uploaded': '已索引 {0} 篇文档。',
    'knowledge.note': '写一条笔记',
    'knowledge.note.title': '标题',
    'knowledge.note.text': '希望智能体记住什么？',
    'knowledge.note.save': '存入',
    'knowledge.note.saved': '已存入。',
    'knowledge.cancel': '取消',
    'knowledge.into': '存入',
    'knowledge.delete': '删除',
    'knowledge.delete.title': '删除这篇文档？',
    'knowledge.delete.confirm': '智能体将不再能检索到它，且无法撤销。',
    'knowledge.delete.confirm.owner': '这篇文档属于 {0}，不是你的。智能体将不再能检索到它，且无法撤销。',
    'knowledge.delete.action': '删除',
    'knowledge.actions': '这篇文档可以做的操作',
    'knowledge.content': '存储的内容',
    'knowledge.content.empty': '这篇文档没有正文。',
    'knowledge.deleted': '已删除。',
    'knowledge.share': '共享给公司',
    'knowledge.unshare': '仅自己可见',
    'knowledge.moving': '正在移动…',
    'knowledge.moved': '已移动。',
    'knowledge.view': '列表显示范围',
    'knowledge.filter.all': '全部内容',
    'knowledge.filter.showing': '仅显示：{0}。',
    'knowledge.owner': '查看他人的知识库',
    'knowledge.owner.placeholder': '对方的用户 ID',
    'knowledge.owner.browse': '打开',
    'knowledge.owner.mine': '回到我的',
    'knowledge.owner.reading': '正在查看 {0} 的知识库，只读。',
    'knowledge.owner.triage': '事件分诊 · {0}',

    'toast.dismiss': '关闭',
    'error.generic': '操作失败。',
    'error.forbidden': '请求被拒绝，请刷新页面后重试。',
    'error.offline': '服务器没有响应。',
    'error.render': '有一部分执行过程没能显示出来。',
  },
};

let current = 'en';

// By language rather than by exact tag: the server resolves zh-TW to zh-CN too, and the two sides
// have to land on the same bundle or the page and its error messages diverge.
export function setLocale(tag) {
  const language = String(tag || 'en').split('-')[0];
  current = STRINGS[language] ? language : 'en';
  document.documentElement.lang = current === 'zh' ? 'zh-CN' : 'en';
  return current;
}

export function locale() {
  return current;
}

// The key is its own fallback, so a missing translation shows something recognisable rather than
// throwing or rendering "undefined".
export function t(key, ...args) {
  const table = STRINGS[current] || STRINGS.en;
  const template = table[key] ?? STRINGS.en[key] ?? key;
  return template.replace(/\{(\d+)\}/g, (whole, index) => {
    const value = args[Number(index)];
    return value === undefined || value === null ? whole : String(value);
  });
}

/**
 * What a field says while it is empty.
 *
 * Two ways of saying it, because the composer is a contenteditable rather than a <textarea>: a
 * field with a `placeholder` property gets the property, and one without gets a `data-placeholder`
 * attribute that CSS draws (see `.composer-field` in composer.css). One function so that a caller —
 * the switcher below, and setRunning in composer.js — does not have to know which kind it is
 * holding.
 *
 * The attribute branch names the field as well, and that is not belt-and-braces: a placeholder is
 * an input's accessible name of last resort, so the textarea this replaced was announced by its own
 * placeholder, and a contenteditable carrying nothing but a data attribute would be a field a
 * screen reader cannot name. Here rather than as a data-i18n-label in the markup because the
 * composer's placeholder changes while a run is going, and a label written once at translation time
 * would go on announcing the idle one.
 */
export function placeholder(element, text) {
  if ('placeholder' in element) element.placeholder = text;
  else {
    element.setAttribute('data-placeholder', text);
    element.setAttribute('aria-label', text);
  }
}

// Re-renders everything carrying a data-i18n key. Called on load and whenever the switcher changes
// the language, so a switch does not need a page reload to take effect.
export function applyTranslations(root = document) {
  // The tab is part of the page: a name that differs by language has to follow the switcher there
  // too, and this is the one call every switch already makes.
  if (root === document) document.title = t('app.title');
  root.querySelectorAll('[data-i18n]').forEach((element) => {
    element.textContent = t(element.dataset.i18n);
  });
  root.querySelectorAll('[data-i18n-placeholder]').forEach((element) => {
    placeholder(element, t(element.dataset.i18nPlaceholder));
  });
  root.querySelectorAll('[data-i18n-title]').forEach((element) => {
    element.title = t(element.dataset.i18nTitle);
  });
  // For a control whose visible content is an icon, so the only name it has is the accessible one.
  root.querySelectorAll('[data-i18n-label]').forEach((element) => {
    element.setAttribute('aria-label', t(element.dataset.i18nLabel));
  });
}

// What this deployment calls itself per language, reported by /api/me as {en: "...", zh: "..."} —
// one entry per language the server supports, whether that name came from app.web.title or from
// the app-title key in its bundle.
//
// Merged into STRINGS rather than replacing it, and skipping a blank, so a language the server said
// nothing about keeps the name the page ships with. Written in at load, for every language at once,
// so that switching language later is a table swap here and never asks the server again — which is
// why the server sends them all rather than the reader's.
export function setAppName(titles) {
  if (!titles || typeof titles !== 'object') return;
  Object.entries(titles).forEach(([language, name]) => {
    const trimmed = String(name || '').trim();
    if (trimmed && STRINGS[language]) STRINGS[language]['app.title'] = trimmed;
  });
}

export const LANGUAGE_NAMES = { en: 'English', zh: '中文' };
