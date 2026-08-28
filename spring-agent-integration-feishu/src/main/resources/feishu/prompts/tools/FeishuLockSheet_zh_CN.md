给一个工作表加锁，这样在你操作期间别人不会去改它。用 FeishuSheetUpdateRange 或
FeishuSheetBatchUpdateRanges 改动工作表之前先调用它，改完之后再调用 FeishuUnlockSheet。

**加锁会返回一个 protectId：在本次任务余下的过程中请记住它。如果本次任务此前已经锁过同一个工作表
并且手上还有它的 protectId，那这个工作表已经是锁着的——不要再锁一次，那没有任何作用，只会拖慢
工作。就用你手上的这把锁去写，到最后再解锁一次。如果你不确定之前那把锁是否还有效，请把它的
protectId 传给 FeishuGetProtectedRanges 看一眼，而不要重新加锁。**
