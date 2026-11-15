# MessageHub V1.0 — Android 消息汇总助手

## 开发环境
- Android Studio (支持 Android Gradle Plugin 8.7.3)
- JDK 17、Android SDK 35、Kotlin 2.0.21
- Android 8.0+ (API 26)
- 需要网络下载 Gradle/Android 构建依赖；运行时不需要联网。

## 构建 APK
1. 在 Android Studio 中选择 Open，打开本项目根目录 `MessageHub`。
2. 等待 Gradle Sync；如提示 SDK 35 缺失，按提示安装。
3. 菜单 Build > Build Bundle(s) / APK(s) > Build APK(s)。
4. APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。
5. 安装到手机，打开 APP，打开“允许本地收集新通知”，再点击通知访问设置，手动授权。

## 功能
- 监听微信 `com.tencent.mm`、QQ `com.tencent.mobileqq`、WhatsApp `com.whatsapp`（及 Business 版）的通知。
- 本地 SQLite 保存；关键词分类；保守的 @ 提及标记；平台数量统计；最近消息列表。
- 通过 Android 文件选择器导入 TXT。WhatsApp 不同地区/版本的导出格式不同，本版本只做基础行解析，不保证正确拆分所有消息。
- 可关闭采集、删除全部本地消息；云端 AI 开关只记录意向，**不联网、不上传、不调用模型**。

## 已知限制与安全说明
- 不是对微信/QQ/WhatsApp 私有数据库的访问；只有产生且可见的通知才能收集。
- 可能出现通知截断、合并、重复或缺失；“@我”只能按通知内容保守推测。
- TXT 导入的时间目前使用导入时序号，**不是真实发送时间**；跨行消息可能拆散；导入平台标记为“历史导入”。请勿用于正式审计或精确时间统计。
- 导入的文件会逐行写入数据库，文件很大时可能耗时；最多处理 100000 行。
- Android 系统/厂商省电设置可能限制通知监听服务。
- 不含网络权限、云端 AI 代码、后台服务器、广告或分析 SDK。
- 默认本地明文 SQLite（受 Android 应用沙箱保护）；不建议在含敏感工作信息的设备上投入生产使用，后续应增加数据库加密、应用锁、自动清理与明确的数据保留策略。
- 本项目未在实际 Android Studio/手机上完成构建及运行测试；如遇 SDK/Gradle 兼容问题需按本机环境调整。

## 后续建议
1. 用测试通知确认三平台兼容性。
2. 实现多行 WhatsApp 历史解析、真实日期、导入预览和撤销。
3. 采用 Android Keystore + 数据库加密，并增加来源白名单。
4. 单独设计云端 AI 授权界面：按会话选择、发送前预览、隐私脱敏、撤回与删除。
5. 添加筛选、搜索、日期图表、群聊@我置信度与待办提取。
