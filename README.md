# 落笔（Android）

把一段话（口播、想表达的意思、疑问）变成一篇完整文章，再一键变成小红书图、长图、HTML，或同步到 Notion / 语雀 / Telegraph / 自定义博客接口。
以「小红书助手 v2.9」源码为基础复制扩展（原项目未改动），独立包名 `com.scarlett.luobi`、独立签名钥匙。

- 版本：1.1.0（versionCode 2），minSdk 29，targetSdk 35
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
| Reader.kt | 结果页（阅读 / 配图、版本、底栏同尺寸渠道格子：小红书 + 置顶渠道 + 更多，可横向滑动） |
| Panel.kt | 分享面板四组渠道及动作 |
| Channels.kt | Notion、语雀、Telegraph、自定义端点、HTML 富文本 / .html 文件、系统分享到指定 APP |
| Render.kt / Share.kt | 照搬小红书助手：卡片、长图、Share.sendImages（只改配色、FileProvider authority、相册名） |
| SettingsUi.kt | 设置首页与全部子页面 |
| Comp.kt / IcData.kt / Theme.kt | P3 组件、icons.py 线条图标（PathParser 渲染）、配色 |

## 意图变量规则
- 变量 `{名字}`，名字只认汉字/字母/数字/下划线（JSON 示例的花括号原样保留）。
- 值原样替换；一行里只要有一个变量为空或不存在，整行删除。
- 写作模板引用当前清洗模板不输出的字段 → 编辑器黄色警告；切换清洗模板 / 删字段时列出受影响模板。
- 最终提示词 = 填好的模板 + 篇幅行（「设置 → 篇幅设置」的默认篇幅，如「篇幅：中篇，正文字数控制在 400–800 字之间。」）；写作时 system 消息要求输出 JSON {title, body, tags}，解析失败按纯文本兜底。

## 1.1.0 改动
- 启动器图标换成「抽象一笔」（adaptive：浅底背景 + 蓝色笔画前景 + monochrome；mipmap 各密度 PNG 兜底）。
- 分享面板改成 4 列同尺寸宫格；微信 / QQ 只发正文全文纯文字（ACTION_SEND text/plain 定向包名，失败弹系统菜单）；微博 / 抖音发长图或配图。
- 修复 sendImagesTo 的 ClipData 可能重复放图（改为按 Uri 去重、每张一项）；小红书流程未改。
- 阅读页删除改写胶囊整条；底栏「小红书」与其它渠道同样是图标+文字格子，置顶上限 8 个，放不下可横向滑动。
- 写作第 ③ 步去掉篇幅选择，换只读提示行（点它进篇幅设置）；新增「设置 → 篇幅设置」。
- 思源宋体裁剪为 GB2312 + ASCII + 常用中文标点子集（pyftsubset），缺字由系统字体回退；开启 R8 混淆/资源压缩。
