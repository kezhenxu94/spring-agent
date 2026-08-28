读取某个会话或某个话题里更早的消息——当一条 @ 提及本身讲不清来由时，就是用它来补上上下文的。
containerIdType 为 chat 时把 containerId 填成 chatId，为 thread 时填成 threadId。返回的消息按时间
从新到旧排列。只能读取你正在与之对话的那个人也在的会话。
