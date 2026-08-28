一篇文档的标题和最新的 revisionId。这个 revisionId 就是写入类工具的 documentRevisionId 参数所要的
值，用于乐观并发控制——不过传 -1 表示最新版本，可以省掉这次调用。
