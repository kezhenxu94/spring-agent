列出一个知识空间或某个 wiki 节点下的子节点：它们的标题、节点 token，以及每个节点背后那篇文档的
token（objToken）和类型（objType）。遍历一个知识库就是这么做的——先看空间的顶层，再看 hasChild
为 true 的节点之下——在用文档、表格或多维表格类工具去读之前，也是这样找出整个空间有哪些文档的。

把 maxDepth 设成大于 1，就能一次调用往下走多层，而不必每个节点调一次：返回的节点是平铺的，每个都
带着它挂在其下的 parentNodeToken，所以可以从这个列表把树重建出来。一次遍历超过节点上限就会停下，
并返回 truncated=true；出现这种情况时，请把某个节点作为 parentNodeToken 去遍历更小的子树，而不要
再用同样的深度去要一遍。pageToken 和 hasMore 永远只描述 parentNodeToken 所指的那一层（不填时即空间
的顶层）；更深的层次各自会被完整翻页。
