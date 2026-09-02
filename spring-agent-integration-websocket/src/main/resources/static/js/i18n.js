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
    'nav.empty': 'Nothing here yet.',
    'nav.signout': 'Sign out',
    'nav.delete': 'Delete conversation',
    'nav.actions': 'What can be done with this conversation',
    'nav.menu': 'Conversations',
    'nav.close': 'Close',

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

    'status.idle': 'Idle',
    'status.attached': 'Running',
    'status.reattaching': 'Reattaching',
    'status.waiting': 'Waiting on you',
    'status.done': 'Done',
    'status.stopped': 'Stopped',
    'status.failed': 'Failed',

    'run.thinking': 'Thinking',
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

    'delete.title': 'Delete this conversation?',
    'delete.confirm': 'What was said in it goes too. This cannot be undone.',
    'delete.action': 'Delete it',
    'delete.done': 'Conversation deleted.',

    'busy.loading': 'Loading…',
    'confirm.cancel': 'Keep it',
    'confirm.working': 'Working…',
    'confirm.ok': 'Go ahead',

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

    'nav.knowledge': 'Knowledge base',
    'nav.sections': 'What the sidebar shows',

    'knowledge.title': 'Knowledge base',
    'knowledge.add': 'Add to the knowledge base',
    'knowledge.source': 'From',
    'knowledge.chunks.label': 'Chunks',
    'knowledge.added': 'Added',
    'knowledge.score': 'Match',
    'knowledge.subtitle': 'What the agent has been told to remember. It is searched on every '
      + 'message, and everything here can also be reached with the knowledge tools in a chat.',
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
    'knowledge.delete.action': 'Delete it',
    'knowledge.actions': 'What can be done with this document',
    'knowledge.content': 'What is stored',
    'knowledge.content.empty': 'This document holds no text.',
    'knowledge.deleted': 'Deleted.',
    'knowledge.share': 'Share with the company',
    'knowledge.unshare': 'Keep to yourself',
    'knowledge.moving': 'Moving it…',
    'knowledge.moved': 'Moved.',
    'knowledge.owner': 'Read another person',
    'knowledge.owner.placeholder': 'Their user id',
    'knowledge.owner.browse': 'Open',
    'knowledge.owner.mine': 'Back to mine',
    'knowledge.owner.reading': 'Reading {0} — read only.',

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
    'nav.empty': '还没有内容。',
    'nav.signout': '退出登录',
    'nav.delete': '删除对话',
    'nav.actions': '这个对话可以做的操作',
    'nav.menu': '对话列表',
    'nav.close': '关闭',

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

    'status.idle': '空闲',
    'status.attached': '执行中',
    'status.reattaching': '重新连接',
    'status.waiting': '等你回答',
    'status.done': '已完成',
    'status.stopped': '已停止',
    'status.failed': '失败',

    'run.thinking': '思考过程',
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

    'delete.title': '删除这个对话？',
    'delete.confirm': '其中的内容也会一并删除，且无法恢复。',
    'delete.action': '删除',
    'delete.done': '对话已删除。',

    'busy.loading': '加载中…',
    'confirm.cancel': '再想想',
    'confirm.working': '处理中…',
    'confirm.ok': '确定',

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

    'nav.knowledge': '知识库',
    'nav.sections': '侧栏显示的内容',

    'knowledge.title': '知识库',
    'knowledge.add': '添加到知识库',
    'knowledge.source': '来源',
    'knowledge.chunks.label': '片段数',
    'knowledge.added': '添加时间',
    'knowledge.score': '匹配度',
    'knowledge.subtitle': '智能体被要求记住的内容。每次对话都会检索这里，'
      + '在聊天中也可以用知识库工具管理同样的内容。',
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
    'knowledge.delete.action': '删除',
    'knowledge.actions': '这篇文档可以做的操作',
    'knowledge.content': '存储的内容',
    'knowledge.content.empty': '这篇文档没有正文。',
    'knowledge.deleted': '已删除。',
    'knowledge.share': '共享给公司',
    'knowledge.unshare': '仅自己可见',
    'knowledge.moving': '正在移动…',
    'knowledge.moved': '已移动。',
    'knowledge.owner': '查看他人的知识库',
    'knowledge.owner.placeholder': '对方的用户 ID',
    'knowledge.owner.browse': '打开',
    'knowledge.owner.mine': '回到我的',
    'knowledge.owner.reading': '正在查看 {0} 的知识库，只读。',

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
    element.placeholder = t(element.dataset.i18nPlaceholder);
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
