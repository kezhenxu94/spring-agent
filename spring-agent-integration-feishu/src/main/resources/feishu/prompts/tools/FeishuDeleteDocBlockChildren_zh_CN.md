按左闭右开的下标区间 [startIndex, endIndex) 删除某个块的子块，下标从 0 数起。

**它不能**删除表格的行或列，也不能删除分栏：那些要用 FeishuUpdateDocBlock 配合 deleteTableRows、
deleteTableColumns 或 deleteGridColumn。它也不能把 TableCell、GridColumn 或 Callout 的子块删到一个
不剩。block_id 由 FeishuListDocBlocks 给出。
