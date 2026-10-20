# 落笔（Android）

把一段话（口播、想表达的意思、疑问）变成一篇完整文章，再一键变成小红书图、长图、HTML，或同步到 Notion / 语雀 / Telegraph / 自定义博客接口。
以「小红书助手 v2.9」源码为基础复制扩展（原项目未改动），独立包名 `com.scarlett.luobi`、独立签名钥匙。

- 版本：1.0.0（versionCode 1），minSdk 29，targetSdk 35
- 技术：Kotlin + Jetpack Compose + Room + WorkManager + EncryptedSharedPreferences

## 构建
```bash
echo "sdk.dir=/opt/android-sdk" > local.properties
# 签名：../signing/signing.properties（storeFile/storePassword/keyAlias/keyPassword），不在源码包内
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```
没有 signing.properties 时会产出未签名 release 包。

## 代码结构（app/src/main/java/com/scarlett/luobi）
| 文件 | 内容 |
|---|---|
| Logic.kt | 意图变量机制：原样替换、空变量整行删除、缺失变量警告、清洗 JSON 容错、文章解析、Markdown/HTML 导出 |
| Data.kt | Room 实体（Note 版本链 / Template / CleanTpl / Channel）、内置 5 模板 + 标准清洗、Settings、Secure（加密存储） |
| Api.kt | OpenAI 兼容调用（沿用 Providers / 报错）；clean（固定 DeepSeek）、write、rewrite、test；配图 |
| Gen.kt | WorkManager 后台生成 / 改写，完成通知 |
| MainActivity.kt | 导航、写作三步流程、最终提示词预览 |
| Reader.kt | 结果页（阅读 / 配图、改写栏、版本、常用渠道、分享到小红书） |
| Panel.kt | 分享面板四组渠道及动作 |
| Channels.kt | Notion、语雀、Telegraph、自定义端点、HTML 富文本 / .html 文件、系统分享到指定 APP |
| Render.kt / Share.kt | 照搬小红书助手：卡片、长图、Share.sendImages（只改配色、FileProvider authority、相册名） |
| SettingsUi.kt | 设置首页与全部子页面 |
| Comp.kt / IcData.kt / Theme.kt | P3 组件、icons.py 线条图标（PathParser 渲染）、配色 |

## 意图变量规则
- 变量 `{名字}`，名字只认汉字/字母/数字/下划线（JSON 示例的花括号原样保留）。
- 值原样替换；一行里只要有一个变量为空或不存在，整行删除。
- 写作模板引用当前清洗模板不输出的字段 → 编辑器黄色警告；切换清洗模板 / 删字段时列出受影响模板。
- 最终提示词 = 填好的模板 + 篇幅行；写作时 system 消息要求输出 JSON {title, body, tags}，解析失败按纯文本兜底。
