package com.scarlett.copytool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 发布渠道失败：给人看的原因 */
class PublishError(msg: String) : Exception(msg)

/** 分享面板里的一项渠道 */
data class ChannelDef(val id: String, val name: String, val icon: List<String>, val group: Int)

object ChannelIds {
    val groups = listOf("直接发到 APP", "复制排版", "一键上传", "存到本地")
    /** 内置渠道；自定义渠道 id 为 "c<id>" */
    val builtIn = listOf(
        ChannelDef("xhs", "小红书", Ic.send, 0),
        ChannelDef("social", "微信 / QQ / 微博 / 抖音", Ic.chat, 0),
        ChannelDef("html", "HTML 富文本", Ic.code, 1),
        ChannelDef("text", "纯文本 / Markdown", Ic.text, 1),
        ChannelDef("notion", "Notion", Ic.notion, 2),
        ChannelDef("yuque", "语雀", Ic.cloud, 2),
        ChannelDef("telegraph", "Telegraph", Ic.globe, 2),
        ChannelDef("long", "长图 / 配图", Ic.longimg, 3),
        ChannelDef("htmlfile", ".html 文件", Ic.download, 3),
    )
    fun all(custom: List<Channel>) = builtIn + custom.map { ChannelDef("c${it.id}", it.name, Ic.globe, 2) }
    fun short(id: String, custom: List<Channel>) = when (id) {
        "long" -> "长图"; "html" -> "HTML"; "text" -> "文本"; "htmlfile" -> ".html"; "social" -> "微信等"
        else -> all(custom).firstOrNull { it.id == id }?.name ?: id
    }
}

private object Http {
    fun call(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String = "application/json; charset=utf-8"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 60000
        if (body != null) c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("User-Agent", "CopyTool-Android/1.0")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to txt
    }
    fun msg(code: Int, txt: String): String {
        val m = try { JSONObject(txt).let { j -> j.optString("message").ifBlank { j.optString("error") }.ifBlank { j.optJSONObject("error")?.optString("message").orEmpty() } } } catch (_: Exception) { "" }
        val hint = when (code) { 401 -> "鉴权失败，Token / 密钥不对或已失效"; 403 -> "没有权限（检查授权范围或会员权限）"; 404 -> "地址或目标不存在"; 429 -> "请求太频繁，请稍后再试"; in 500..599 -> "对方服务器出错"; else -> "请求失败" }
        return "$hint（HTTP $code）" + if (m.isNotBlank()) "：$m" else if (txt.isNotBlank()) "：${txt.take(160)}" else ""
    }
}

/** Notion 官方 API：POST /v1/pages，正文转成 blocks；目标可以是页面或数据库 */
object Notion {
    private const val API = "https://api.notion.com/v1"
    private fun h(token: String) = mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")
    /** 从链接或 id 里取出 32 位 id */
    fun idOf(s: String): String {
        val hex = Regex("[0-9a-fA-F]{32}").findAll(s.replace("-", "")).lastOrNull()?.value ?: return s.trim()
        return hex.lowercase()
    }
    private fun rt(text: String) = JSONArray().apply { text.chunked(1900).forEach { put(JSONObject().put("type", "text").put("text", JSONObject().put("content", it))) } }
    fun blocks(body: String, tags: List<String>): List<JSONObject> {
        val ps = Export.paras(body)
        val l = ps.map { p ->
            if (isHead(p, ps.size)) JSONObject().put("object", "block").put("type", "heading_2").put("heading_2", JSONObject().put("rich_text", rt(headText(p))))
            else JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", rt(p.replace("**", ""))))
        }.toMutableList()
        if (tags.isNotEmpty()) l += JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", rt(tags.joinToString(" ") { "#$it" })))
        return l
    }
    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>): String = withContext(Dispatchers.IO) {
        val s = Settings(ctx); val token = Secure.get(ctx, "notion_token")
        if (token.isBlank() || s.notionTarget.isBlank()) throw ConfigError("还没有配置 Notion。请到「设置 → 发布渠道 → Notion」填集成 Token 和目标页面 / 数据库。")
        val target = idOf(s.notionTarget)
        val bl = blocks(body, tags)
        val req = JSONObject()
        if (s.notionType == "database") {
            var titleProp = s.notionTitleProp
            if (titleProp.isBlank()) {
                val (c, t) = Http.call("GET", "$API/databases/$target", h(token), null)
                if (c !in 200..299) throw PublishError(Http.msg(c, t))
                val props = JSONObject(t).optJSONObject("properties") ?: JSONObject()
                titleProp = props.keys().asSequence().firstOrNull { props.optJSONObject(it)?.optString("type") == "title" } ?: "Name"
            }
            val props = JSONObject().put(titleProp, JSONObject().put("title", rt(title)))
            if (s.notionTagsProp.isNotBlank() && tags.isNotEmpty())
                props.put(s.notionTagsProp, JSONObject().put("multi_select", JSONArray().apply { tags.forEach { put(JSONObject().put("name", it.replace(",", " "))) } }))
            req.put("parent", JSONObject().put("database_id", target)).put("properties", props)
        } else {
            req.put("parent", JSONObject().put("page_id", target)).put("properties", JSONObject().put("title", JSONObject().put("title", rt(title))))
        }
        req.put("children", JSONArray(bl.take(100)))
        val (code, txt) = Http.call("POST", "$API/pages", h(token), req.toString())
        if (code !in 200..299) throw PublishError(Http.msg(code, txt))
        val page = JSONObject(txt)
        // 一次最多 100 个 block，剩下的分批追加
        bl.drop(100).chunked(100).forEach { chunk ->
            val (c2, t2) = Http.call("PATCH", "$API/blocks/${page.optString("id")}/children", h(token), JSONObject().put("children", JSONArray(chunk)).toString())
            if (c2 !in 200..299) throw PublishError("页面已创建，但后半段正文追加失败：" + Http.msg(c2, t2))
        }
        page.optString("url")
    }
    suspend fun test(ctx: Context): String = withContext(Dispatchers.IO) {
        val token = Secure.get(ctx, "notion_token"); val s = Settings(ctx)
        if (token.isBlank()) throw ConfigError("请先填写 Notion 集成 Token")
        val (c, t) = Http.call("GET", "$API/users/me", h(token), null)
        if (c !in 200..299) throw PublishError(Http.msg(c, t))
        if (s.notionTarget.isNotBlank()) {
            val path = if (s.notionType == "database") "databases" else "pages"
            val (c2, t2) = Http.call("GET", "$API/$path/${idOf(s.notionTarget)}", h(token), null)
            if (c2 !in 200..299) throw PublishError("Token 有效，但访问不到目标：" + Http.msg(c2, t2) + "。请在 Notion 里把该页面 / 数据库「连接」到你的集成。")
        }
        "连接正常" + if (s.notionTarget.isNotBlank()) "，能访问目标" else ""
    }
}

/** 语雀开放 API：X-Auth-Token；POST /api/v2/repos/{namespace}/docs（Markdown） */
object Yuque {
    private const val API = "https://www.yuque.com/api/v2"
    private fun h(token: String) = mapOf("X-Auth-Token" to token)
    suspend fun repos(ctx: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val token = Secure.get(ctx, "yuque_token"); if (token.isBlank()) throw ConfigError("请先填写语雀 Token")
        val (c, t) = Http.call("GET", "$API/user", h(token), null)
        if (c !in 200..299) throw PublishError(Http.msg(c, t))
        val login = JSONObject(t).optJSONObject("data")?.optString("login").orEmpty()
        val (c2, t2) = Http.call("GET", "$API/users/$login/repos?type=Book", h(token), null)
        if (c2 !in 200..299) throw PublishError(Http.msg(c2, t2))
        val a = JSONObject(t2).optJSONArray("data") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("namespace") to o.optString("name") } }
    }
    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>): String = withContext(Dispatchers.IO) {
        val s = Settings(ctx); val token = Secure.get(ctx, "yuque_token")
        if (token.isBlank() || s.yuqueNs.isBlank()) throw ConfigError("还没有配置语雀。请到「设置 → 发布渠道 → 语雀」填个人 Token 并选择知识库。")
        val md = Export.markdown(title, body, tags).substringAfter("\n").trim()
        val req = JSONObject().put("title", title).put("format", "markdown").put("body", md).put("public", if (s.yuquePublic) 1 else 0)
        val (c, t) = Http.call("POST", "$API/repos/${s.yuqueNs.trim('/')}/docs", h(token), req.toString())
        if (c !in 200..299) throw PublishError(Http.msg(c, t) + if (c == 401 || c == 403) "。个人调用语雀 API 可能需要会员，请在语雀账号设置里确认。" else "")
        val slug = JSONObject(t).optJSONObject("data")?.optString("slug").orEmpty()
        "https://www.yuque.com/${s.yuqueNs.trim('/')}/$slug"
    }
}

/** Telegraph：首次自动 createAccount（token 加密存本机），createPage 返回链接 */
object Telegraph {
    private const val API = "https://api.telegra.ph"
    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>): String = withContext(Dispatchers.IO) {
        val s = Settings(ctx)
        var token = Secure.get(ctx, "telegraph_token")
        if (token.isBlank()) {
            val (c, t) = Http.call("POST", "$API/createAccount", emptyMap(), JSONObject().put("short_name", "copytool").put("author_name", s.telegraphName).toString())
            val j = JSONObject(t.ifBlank { "{}" })
            if (c !in 200..299 || !j.optBoolean("ok")) throw PublishError("创建 Telegraph 账号失败：" + j.optString("error").ifBlank { Http.msg(c, t) })
            token = j.getJSONObject("result").getString("access_token"); Secure.set(ctx, "telegraph_token", token)
        }
        val ps = Export.paras(body)
        val nodes = JSONArray()
        ps.forEach { p -> nodes.put(JSONObject().put("tag", if (isHead(p, ps.size)) "h4" else "p").put("children", JSONArray().put(if (isHead(p, ps.size)) headText(p) else p.replace("**", "")))) }
        if (tags.isNotEmpty()) nodes.put(JSONObject().put("tag", "p").put("children", JSONArray().put(tags.joinToString(" ") { "#$it" })))
        val req = JSONObject().put("access_token", token).put("title", title.take(256)).put("author_name", s.telegraphName).put("content", nodes)
        val (c, t) = Http.call("POST", "$API/createPage", emptyMap(), req.toString())
        val j = JSONObject(t.ifBlank { "{}" })
        if (c !in 200..299 || !j.optBoolean("ok")) throw PublishError("Telegraph 发布失败：" + j.optString("error").ifBlank { Http.msg(c, t) })
        j.getJSONObject("result").getString("url")
    }
}

/** 通用发布接口（WordPress / Halo / Ghost 等） */
object Custom {
    fun body(ch: Channel, title: String, body: String, tags: List<String>): String {
        val content = when (ch.format) { "html" -> Export.htmlBody(body, tags); "markdown" -> Export.markdown(title, body, tags).substringAfter("\n").trim(); else -> body }
        return Export.jsonTpl(ch.bodyTpl, title, content, tags)
    }
    private fun headers(ctx: Context, ch: Channel): Map<String, String> {
        val secret = Secure.get(ctx, "ch_${ch.id}")
        return if (secret.isNotBlank() && ch.authHeader.isNotBlank()) mapOf(ch.authHeader to ch.authPrefix + secret) else emptyMap()
    }
    suspend fun publish(ctx: Context, ch: Channel, title: String, body: String, tags: List<String>): String = withContext(Dispatchers.IO) {
        if (ch.url.isBlank()) throw ConfigError("「${ch.name}」还没有填接口地址")
        val (c, t) = Http.call(ch.method, ch.url, headers(ctx, ch), body(ch, title, body, tags))
        if (c !in 200..299) throw PublishError(Http.msg(c, t))
        val link = try { JSONObject(t).let { j -> j.optString("link").ifBlank { j.optString("url") }.ifBlank { j.optJSONObject("data")?.optString("url").orEmpty() } } } catch (_: Exception) { "" }
        link.ifBlank { "已发布（HTTP $c）" }
    }
    /** 测试连接：只发 GET，不会真的发布 */
    suspend fun test(ctx: Context, ch: Channel): String = withContext(Dispatchers.IO) {
        if (!ch.url.startsWith("http")) throw ConfigError("接口地址要以 http:// 或 https:// 开头")
        val (c, t) = Http.call("GET", ch.url, headers(ctx, ch), null)
        when {
            c in 200..299 -> "连接正常（HTTP $c），鉴权已通过"
            c == 405 -> "服务器可达（HTTP 405：该地址不接受 GET，通常说明是只收 POST/PUT 的发布接口）"
            c == 401 || c == 403 -> throw PublishError(Http.msg(c, t))
            else -> throw PublishError(Http.msg(c, t))
        }
    }
}

/** 复制排版 / 本地导出 / 系统分享到指定 APP */
object Out {
    fun copyHtml(ctx: Context, title: String, body: String, tags: List<String>) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val html = "<h1>${Export.esc(title)}</h1>\n" + Export.htmlBody(body, tags)
        cm.setPrimaryClip(ClipData.newHtmlText(title, Export.plain(title, body.lines().joinToString("\n") { if (it.startsWith("#")) headText(it) else it }, tags), html))
    }
    /** 存到「下载/文案台」，返回文件名 */
    fun saveHtml(ctx: Context, title: String, body: String, tags: List<String>): String {
        val name = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "").take(40).ifBlank { "文章" } + "_" + System.currentTimeMillis() % 100000 + ".html"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/文案台")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw PublishError("无法在「下载」里创建文件")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(Export.htmlDoc(title, body, tags).toByteArray()) } ?: throw PublishError("无法写入文件")
        return name
    }
    val socialApps = listOf("微信" to "com.tencent.mm", "QQ" to "com.tencent.mobileqq", "微博" to "com.sina.weibo", "抖音" to "com.ss.android.ugc.aweme")
    /** 指定包名分享图片；失败时弹系统菜单 */
    fun sendImagesTo(ctx: Context, pkg: String?, uris: ArrayList<android.net.Uri>, text: String) {
        if (uris.isEmpty()) return
        val it = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]) else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)).apply {
            type = "image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { u -> addItem(ClipData.Item(u)) } }
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val x = if (pkg != null) Intent(it).setPackage(pkg) else null
        val ok = x != null && ctx.packageManager.queryIntentActivities(x, 0).isNotEmpty()
        if (pkg != null && !ok) toast(ctx, "未检测到该 APP，请选择分享方式")
        try { ctx.startActivity((if (ok) x!! else Intent.createChooser(it, "分享到")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { ctx.startActivity(Intent.createChooser(it, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
