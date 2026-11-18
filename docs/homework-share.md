# 教师作业转发

教师在作业管理中发布作业后，点击「转发给家长」，由微信弹出会话选择界面。草稿和已关闭作业不显示此按钮。卡片标题为「名远教育｜老师布置作业啦，点击查看」。转发只由用户主动触发，不自动发送消息。

卡片打开 `/pages/homework-share/homework-share`，不携带教师、家长、学生、班级或报名编号，也不公开作业附件。入口通过现有 `/getInfo` 和 `/miniapp/parent/account` 接口确认接收人的身份与孩子；单孩子直接进入现有作业列表，多孩子先选择孩子。教师兼家长账号切换至家长工作台。没有家长角色时提示使用家长账号，没有孩子时引导添加孩子。作业内容仍通过现有家长作业接口及权限查询。

未登录或会话失效时跳转登录，登录后回到作业入口；回跳目标为固定白名单，不接受任意地址。离开页面或切换账号后，旧请求不能触发跳转或选择旧账号的孩子。

仅修改小程序，不需要数据库迁移或新增后端接口。上线需要上传并发布包含新页面及图片的小程序版本。旧版本没有此页面。微信聊天卡片外框、应用名称和图标由微信控制，项目自定义标题和封面。

## 封面

项目资产：`WeChatMiniApp/assets/homework-share.jpg`。使用内置 image_gen 工具，以用户提供的商标为参考生成；仅作 JPEG 编码压缩，文件约 172 KiB。生成请求未暴露可选择的模型参数。提示词如下：

> Create a polished WeChat mini-program homework share cover for 名远教育. Use case: ads-marketing. Input image is the exact supplied school trademark; retain its lettering 名远教育 通往星辰 / Mingyuan Education To the Future and blue gold sail motif faithfully in a wide white header area. Output artwork only, landscape aspect ratio 5:4 (1000x800), not a phone screenshot or chat UI. Below trademark: large beautifully typeset legible navy Chinese headline “课后作业”, subtitle “温故知新 · 每日进步”, small tasteful call to action “点击查看作业”. Blue and warm white palette with restrained golden accents echo logo, open notebook, pencil and subtle paper-plane/star illustration on lower right, professional gentle educational design, ample whitespace and safe margins, no student names, teacher names, dates, QR codes, or invented logos. This cover accompanies a share link that opens each parent’s own homework list.

分享参数和按钮方式参考：[腾讯云 CloudBase 小程序分享文档](https://docs.cloudbase.net/recipes/add-share-with-params-miniprogram)。

## 验证

运行 `node --test WeChatMiniApp/tests/*.test.cjs`，478 项通过，含新增分享入口、孩子隔离、登录回跳和会话失效用例。

发布前仍需微信真机验收：教师发布作业并转发给个人/群；家长分别在已登录、未登录、登录过期状态点开；多孩子家庭选择孩子；非该班家长只能看到自己的作业。检查聊天卡片封面和标题，以及转发按钮不会误触作业批阅。自动化测试不代替微信客户端实际分享验证。
