上传一个本地图片或文件，并把它绑定到文档里一个已经存在的 Image 或 File 块上。这是插入图片或附件的
第二步：第一步是拿到那个块的真实 block_id，第三步是用 FeishuUpdateDocBlock 配合 replaceImage 或
replaceFile，把本工具返回的 fileToken 填进 token 字段。完整流程见 FeishuDocBlockGuide。

documentId 指该块所属的文档。上传本身并不需要它——只有 block_id 就够了——但它用来表明正在写入的是谁的文档，
不填的调用会被拒绝。
