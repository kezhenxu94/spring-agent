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

    'empty.title': 'Ask the agent to do something.',
    'empty.body': 'It works on the server, so you can close this tab and come back. '
      + 'Whatever it is doing will still be here.',

    'composer.placeholder': 'Ask anything. Shift+Enter for a new line.',
    'composer.placeholder.running': 'Add something and it joins the run…',
    'composer.send': 'Send',
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

    'task.none': 'No scheduled work.',
    'task.runs': '{0}/{1} runs',
    'task.cancel': 'Cancel',
    'task.cancelled': 'Scheduled task cancelled.',
    'task.background': 'unattended',
    'task.open': 'Open',

    'delete.confirm': 'Delete this conversation? What was said in it goes too.',
    'delete.done': 'Conversation deleted.',

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

    'toast.dismiss': 'Dismiss',
    'error.generic': 'That did not work.',
    'error.forbidden': 'Refused. Reload the page and try again.',
    'error.offline': 'The server is not answering.',
    'error.render': 'Could not draw part of the run.',
  },

  zh: {
    'app.title': 'Spring Agent',

    'nav.new': '新建对话',
    'nav.conversations': '对话',
    'nav.tasks': '定时任务',
    'nav.untitled': '未命名',
    'nav.empty': '还没有内容。',
    'nav.signout': '退出登录',
    'nav.delete': '删除对话',

    'empty.title': '交给智能体一件事。',
    'empty.body': '它在服务端执行，可以随时关掉这个页面。回来时正在做的事仍然在。',

    'composer.placeholder': '随便问点什么，Shift+Enter 换行。',
    'composer.placeholder.running': '继续输入会插入到当前这轮…',
    'composer.send': '发送',
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

    'task.none': '没有定时任务。',
    'task.runs': '{0}/{1} 次',
    'task.cancel': '取消',
    'task.cancelled': '定时任务已取消。',
    'task.background': '后台执行',
    'task.open': '打开',

    'delete.confirm': '删除这个对话？其中的内容也会一并删除。',
    'delete.done': '对话已删除。',

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
  root.querySelectorAll('[data-i18n]').forEach((element) => {
    element.textContent = t(element.dataset.i18n);
  });
  root.querySelectorAll('[data-i18n-placeholder]').forEach((element) => {
    element.placeholder = t(element.dataset.i18nPlaceholder);
  });
  root.querySelectorAll('[data-i18n-title]').forEach((element) => {
    element.title = t(element.dataset.i18nTitle);
  });
}

export const LANGUAGE_NAMES = { en: 'English', zh: '中文' };
