根据提示词生成一张图像，并返回它的 URL——一个 file:// 形式的地址，指明图像存在本机的什么位置，要用
markdown 以 ![描述](file:///绝对路径.png) 的形式展示出来。也支持以参考图生成：本地文件必须先用
PublishFile 发布（visibility=public，ttl=30m），再把它返回的 URL 作为 referenceImages 传入。
