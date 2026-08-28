按 id 读取一条飞书消息——想看用户回复或引用的那条消息时，就用它。如果返回结果里带有 threadId，说明
这条消息属于某个话题，那就接着调用 FeishuReadMessageHistory，传 containerIdType=thread 和
containerId=threadId，把这个话题的其余部分取回来。只能读取你正在与之对话的那个人也在的会话里的
消息。
