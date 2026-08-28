复制一个电子表格里的某个工作表，副本放在原表之后，返回它的 sheetId、标题和序号。这个 sheetId 可以
直接用于 FeishuSheetUpdateRange、FeishuSheetBatchUpdateRanges 或 FeishuSheetReadRange。这属于改结构
而不是改内容，所以不需要先调用 FeishuLockSheet。
