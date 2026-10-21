package com.scarlett.luobi

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
    /** 内置渠道（顺序即「更多」面板顺序）；自定义渠道 id 为 "c<id>" */
    val builtIn = listOf(
        ChannelDef("xhs", "小红书", Ic.send, 0),
        ChannelDef("wechat", "微信", Ic.chat, 0),
        ChannelDef("qq", "QQ", Ic.qq, 0),
        ChannelDef("douyin", "抖音", Ic.music, 0),
        ChannelDef("notion", "Notion", Ic.notion, 2),
        ChannelDef("long", "长图", Ic.longimg, 3),
        ChannelDef("weibo", "微博", Ic.weibo, 0),
        ChannelDef("html", "HTML", Ic.code, 1),
        ChannelDef("md", "Markdown", Ic.md, 1),
        ChannelDef("copy", "复制全文", Ic.copy2, 1),
        ChannelDef("yuque", "语雀", Ic.text, 2),
        ChannelDef("telegraph", "Telegraph", Ic.globe, 2),
    )
    val more = ChannelDef(ShareBar.MORE, "更多", Ic.more, 9)
    fun allIds(@Suppress("UNUSED_PARAMETER") c: android.content.Context? = null) = builtIn.map { it.id }
    fun all(custom: List<Channel>) = builtIn + custom.map { ChannelDef("c${it.id}", it.name, Ic.cloud, 2) }
    fun def(id: String, custom: List<Channel>) = if (id == ShareBar.MORE) more else all(custom).firstOrNull { it.id == id }
    fun short(id: String, custom: List<Channel>) = def(id, custom)?.name ?: id
    /** 「更多」面板右侧的用途说明 */
    fun note(id: String) = when (id) {
        "xhs" -> "配图卡片"; "wechat", "qq", "weibo" -> "发长图"; "douyin" -> "复制文案并打开"; "long" -> "存相册"; "html" -> "分享 .html 文件"
        "md" -> "分享 .md 文件"; "copy" -> "剪贴板"; "notion", "yuque", "telegraph" -> "发布"; else -> "发布"
    }
}

internal object Http {
    fun call(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String = "application/json; charset=utf-8"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 60000
        if (body != null) c.setRequestProperty("Content-Type", contentType)
        c.setRequestProperty("User-Agent", "LuoBi-Android/1.0")
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

/**
 * Notion 官方 API（1.12.1：Notion-Version 2025-09-03）。
 * - 页面：GET /v1/pages/{id} 测试；POST /v1/pages parent.page_id 建子页面。绝不调数据库接口。
 * - 数据库：GET /v1/databases/{id} → data_sources[0].id → GET /v1/data_sources/{ds} 取属性 → POST /v1/pages parent.data_source_id。
 *   用户粘的若本身是 data source ID（databases 404），直接 GET /v1/data_sources/{id}。
 * 测试与发布共用 resolve()，目标类型一致。
 */
object Notion {
    private const val API = "https://api.notion.com/v1"
    const val VERSION = "2025-09-03"
    /** 可替换的 HTTP（单元测试注入假的） */
    var transport: (method: String, url: String, headers: Map<String, String>, body: String?) -> Pair<Int, String> =
        { m, u, h, b -> Http.call(m, u, h, b) }
    private fun h(token: String) = mapOf("Authorization" to "Bearer $token", "Notion-Version" to VERSION)

    private val HEX32 = Regex("([0-9a-fA-F]{32})$")
    private val UUID = Regex("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
    /**
     * 从链接或 ID 里取目标 ID，返回带横线 UUID；取不到返回 null。
     * 规则：去掉 ?query 和 #fragment（?v= 视图 ID、&source=copy_link 都丢弃）→ 取最后一段路径 →
     * 末尾的 32 位十六进制（可带横线，可在 slug-标题-<id> 后面）。
     */
    fun parseId(input: String): String? {
        var s = input.trim()
        s = s.substringBefore('#').substringBefore('?').trimEnd('/')
        val last = s.substringAfterLast('/')
        UUID.find(last)?.let { return it.groupValues[1].lowercase() }
        val m = HEX32.find(last.replace("-", "")) ?: return null
        val h = m.groupValues[1].lowercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
    /** 链接里带 ?v= 说明是数据库视图链接 */
    fun looksLikeDatabaseLink(input: String) = Regex("[?&]v=[0-9a-fA-F]{32}").containsMatchIn(input)
    @Deprecated("用 parseId") fun idOf(s: String): String = parseId(s) ?: s.trim()

    data class Target(val type: String, val id: String, val parentJson: JSONObject, val titleProp: String, val tested: List<String>, val schema: JSONObject = JSONObject())

    private fun call(token: String, method: String, path: String, body: String? = null): Pair<Int, String> = transport(method, "$API$path", h(token), body)

    private fun fail(type: String, method: String, path: String, code: Int, txt: String, extra: String = ""): PublishError {
        val j = runCatching { JSONObject(txt) }.getOrNull()
        val ec = j?.optString("code").orEmpty(); val em = j?.optString("message").orEmpty().ifBlank { txt.take(200) }
        val typeLabel = when (type) { "database" -> "数据库（新增一行）"; "cover" -> "封面"; else -> "页面（建子页面）" }
        return PublishError("Notion 请求失败：$method /v1$path（目标类型：$typeLabel，HTTP $code" + (if (ec.isNotBlank()) "，$ec" else "") + "）" +
            (if (em.isNotBlank()) "：$em" else "") + (if (extra.isNotBlank()) "\n$extra" else ""))
    }

    /** 测试与发布共用：按目标类型解析出 parent */
    fun resolve(token: String, type: String, rawTarget: String, titlePropSetting: String = ""): Target {
        val id = parseId(rawTarget) ?: throw ConfigError("没能从「$rawTarget」里找到 Notion ID。请粘贴页面 / 数据库链接，或 32 位 ID。")
        val tested = mutableListOf<String>()
        if (type != "database") {
            val path = "/pages/$id"; tested += "GET /v1$path"
            val (c, t) = call(token, "GET", path)
            if (c !in 200..299) {
                val hint = buildString {
                    if (looksLikeDatabaseLink(rawTarget)) append("这条链接带 ?v=，像是数据库链接：请把目标类型改成「数据库（新增一行）」。")
                    else if (c == 404) append("请确认该页面右上角「…→ 连接」里已添加你的集成。")
                }
                throw fail(type, "GET", path, c, t, hint)
            }
            return Target("page", id, JSONObject().put("type", "page_id").put("page_id", id), "title", tested)
        }
        // 数据库：先 databases，拿 data_sources；404 再把它当 data source ID
        var dsId: String
        val dbPath = "/databases/$id"; tested += "GET /v1$dbPath"
        val (c, t) = call(token, "GET", dbPath)
        if (c in 200..299) {
            val arr = JSONObject(t).optJSONArray("data_sources")
            dsId = arr?.optJSONObject(0)?.optString("id").orEmpty()
            if (dsId.isBlank()) throw PublishError("Notion 数据库 $id 没有返回 data_sources（GET /v1$dbPath）。")
        } else if (c == 404 || c == 400) {
            val dsPath = "/data_sources/$id"; tested += "GET /v1$dsPath"
            val (c2, t2) = call(token, "GET", dsPath)
            if (c2 !in 200..299) throw fail(type, "GET", dbPath, c, t, "也试了 GET /v1$dsPath：HTTP $c2。请确认数据库右上角「…→ 连接」里已添加你的集成。")
            dsId = id
            return Target("database", id, JSONObject().put("type", "data_source_id").put("data_source_id", dsId), titleOf(JSONObject(t2), titlePropSetting), tested, JSONObject(t2).optJSONObject("properties") ?: JSONObject())
        } else throw fail(type, "GET", dbPath, c, t)
        val dsPath = "/data_sources/$dsId"; tested += "GET /v1$dsPath"
        val (c3, t3) = call(token, "GET", dsPath)
        if (c3 !in 200..299) throw fail(type, "GET", dsPath, c3, t3)
        return Target("database", id, JSONObject().put("type", "data_source_id").put("data_source_id", dsId), titleOf(JSONObject(t3), titlePropSetting), tested, JSONObject(t3).optJSONObject("properties") ?: JSONObject())
    }
    private fun titleOf(ds: JSONObject, setting: String): String {
        if (setting.isNotBlank()) return setting
        val props = ds.optJSONObject("properties") ?: return "Name"
        return props.keys().asSequence().firstOrNull { props.optJSONObject(it)?.optString("type") == "title" } ?: "Name"
    }

    private fun rt(text: String) = JSONArray().apply { text.chunked(1900).forEach { put(JSONObject().put("type", "text").put("text", JSONObject().put("content", it))) } }
    /** 行内粗体 → rich_text annotations.bold */
    private fun rtMd(text: String) = JSONArray().apply { Md.spans(text).forEach { (t, b) -> t.chunked(1900).forEach { c -> put(JSONObject().put("type", "text").put("text", JSONObject().put("content", c)).apply { if (b) put("annotations", JSONObject().put("bold", true)) }) } } }
    private fun blk(type: String, body: JSONObject = JSONObject()) = JSONObject().put("object", "block").put("type", type).put(type, body)
    fun blocks(body: String, tags: List<String>, pics: List<Pic> = emptyList(), showCredit: Boolean = true): List<JSONObject> {
        val ps = Export.paras(body); val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val l = ps.flatMapIndexed { i, p ->
            val m = Md.line(p, ps.size)
            listOf(when (m.kind) {
                MdKind.H2 -> blk("heading_2", JSONObject().put("rich_text", rtMd(m.text)))
                MdKind.H3 -> blk("heading_3", JSONObject().put("rich_text", rtMd(m.text)))
                MdKind.QUOTE -> blk("quote", JSONObject().put("rich_text", rtMd(m.text)))
                MdKind.BULLET -> blk("bulleted_list_item", JSONObject().put("rich_text", rtMd(m.text)))
                MdKind.NUM -> blk("numbered_list_item", JSONObject().put("rich_text", rtMd(m.text)))
                MdKind.HR -> blk("divider")
                MdKind.P -> blk("paragraph", JSONObject().put("rich_text", rtMd(m.text)))
            }) + (bp[i] ?: emptyList()).map { pic -> blk("image", JSONObject().put("type", "external").put("external", JSONObject().put("url", pic.url))
                .apply { val cap = pic.captionLine(showCredit); if (cap.isNotBlank()) put("caption", rt(cap)) }) }
        }.toMutableList()
        if (tags.isNotEmpty()) l += blk("paragraph", JSONObject().put("rich_text", rt(tags.joinToString(" ") { "#$it" })))
        return l
    }
    /** 发布结果：coverNote 非空时是封面没设上的提示（不影响发布） */
    data class Result(val url: String, val coverNote: String = "")

    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String = uploadFull(ctx, title, body, tags, pics).url
    suspend fun uploadFull(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList(), category: String = "", writerModel: String = ""): Result = withContext(Dispatchers.IO) {
        val s = Settings(ctx); val token = Secure.get(ctx, "notion_token")
        if (token.isBlank() || s.notionTarget.isBlank()) throw ConfigError("还没有配置 Notion。请到「设定 → 发布渠道 → Notion」填集成 Token 和目标页面 / 数据库。")
        val cover = firstImage(pics, Export.paras(body).size)?.let { coverSourceOf(it) }
        // 摘要只在数据库目标、且发布时现生成（用写作模型）；失败不挡发布
        val summarize: () -> String = { kotlinx.coroutines.runBlocking { Summary.make(ctx, title, body, writerModel) } }
        publishFull(token, s.notionType, s.notionTarget, s.notionTitleProp, s.notionTagsProp, title, blocks(body, tags, pics, s.showCredit), tags, cover,
            Meta(category, summarize))
    }
    fun publish(token: String, type: String, rawTarget: String, titleProp: String, tagsProp: String, title: String, bl: List<JSONObject>, tags: List<String>): String =
        publishFull(token, type, rawTarget, titleProp, tagsProp, title, bl, tags, null).url

    // ---------- 1.13：封面 = 正文第一张图 ----------
    /** 封面来源：远程 https → external；本地文件 → File Upload */
    sealed class CoverSource {
        data class External(val url: String) : CoverSource()
        data class Local(val file: java.io.File) : CoverSource()
    }
    /** 正文里的第一张图：按插入段落顺序，同段按原顺序 */
    fun firstImage(pics: List<Pic>, paraCount: Int): Pic? =
        pics.withIndex().sortedWith(compareBy({ it.value.after.coerceIn(0, (paraCount - 1).coerceAtLeast(0)) }, { it.index })).firstOrNull()?.value
    fun coverSourceOf(p: Pic): CoverSource? = when {
        p.url.startsWith("https://") -> CoverSource.External(p.url)
        p.url.startsWith("file://") -> java.io.File(p.url.removePrefix("file://")).takeIf { it.isFile }?.let { CoverSource.Local(it) }
        p.local.isNotBlank() && java.io.File(p.local).isFile -> CoverSource.Local(java.io.File(p.local))
        else -> null
    }
    /** multipart 上传（单元测试可替换） */
    var sendFile: (url: String, headers: Map<String, String>, filename: String, mime: String, bytes: ByteArray) -> Pair<Int, String> = { u, h, f, m, b -> multipart(u, h, f, m, b) }
    private fun multipart(url: String, headers: Map<String, String>, filename: String, mime: String, bytes: ByteArray): Pair<Int, String> {
        val boundary = "----LuoBi" + System.nanoTime()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 60000; c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            o.write(bytes); o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to txt
    }
    private fun mimeOf(f: java.io.File) = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
    /** POST /v1/file_uploads → POST /v1/file_uploads/{id}/send，返回 file_upload id */
    fun uploadFile(token: String, f: java.io.File): String {
        val mime = mimeOf(f); val name = f.name.let { if (it.contains('.')) it else "$it.jpg" }
        val (c, t) = call(token, "POST", "/file_uploads", JSONObject().put("filename", name).put("content_type", mime).toString())
        if (c !in 200..299) throw fail("cover", "POST", "/file_uploads", c, t)
        val id = JSONObject(t).optString("id"); if (id.isBlank()) throw PublishError("POST /v1/file_uploads 没有返回 id")
        val path = "/file_uploads/$id/send"
        val (c2, t2) = sendFile("$API$path", h(token), name, mime, f.readBytes())
        if (c2 !in 200..299) throw fail("cover", "POST", path, c2, t2)
        return id
    }
    /** PATCH /v1/pages/{id} 设封面；失败返回提示，不抛 */
    fun setCover(token: String, pageId: String, src: CoverSource): String = try {
        val cover = when (src) {
            is CoverSource.External -> JSONObject().put("type", "external").put("external", JSONObject().put("url", src.url))
            is CoverSource.Local -> JSONObject().put("type", "file_upload").put("file_upload", JSONObject().put("id", uploadFile(token, src.file)))
        }
        val path = "/pages/$pageId"
        val (c, t) = call(token, "PATCH", path, JSONObject().put("cover", cover).toString())
        if (c !in 200..299) throw fail("cover", "PATCH", path, c, t)
        ""
    } catch (e: Throwable) { "封面没设上（文章已发布）：" + (e.message ?: e.javaClass.simpleName).take(200) }

    /** 1.15：数据库属性要填的内容；summarize 只在数据库里有「文章摘要」时才调用 */
    class Meta(val category: String = "", val summarize: (() -> String)? = null)
    const val PROP_CATEGORY = "文章分类"; const val PROP_TAGS = "主题标签"; const val PROP_SUMMARY = "文章摘要"
    /** 按数据源 schema 填「文章分类 / 主题标签 / 文章摘要」：只填存在且类型兼容的；返回跳过/失败说明 */
    fun fillProps(props: JSONObject, schema: JSONObject, tags: List<String>, meta: Meta): List<String> {
        val notes = mutableListOf<String>()
        fun typeOf(n: String) = schema.optJSONObject(n)?.optString("type").orEmpty()
        fun check(n: String, want: String): Boolean = when (typeOf(n)) {
            want -> true
            "" -> { notes += "数据库里没有「$n」"; false }
            else -> { notes += "「$n」类型是 ${typeOf(n)}，不是 $want，已跳过"; false }
        }
        if (meta.category.isNotBlank() && check(PROP_CATEGORY, "select"))
            props.put(PROP_CATEGORY, JSONObject().put("select", JSONObject().put("name", meta.category)))
        if (tags.isNotEmpty() && check(PROP_TAGS, "multi_select"))
            props.put(PROP_TAGS, JSONObject().put("multi_select", JSONArray().apply { tags.distinct().forEach { put(JSONObject().put("name", it.replace(",", " ").take(100))) } }))
        if (meta.summarize != null && check(PROP_SUMMARY, "rich_text")) {
            val sum = runCatching { meta.summarize.invoke().trim() }
            val text = sum.getOrNull().orEmpty()
            if (text.isNotBlank()) props.put(PROP_SUMMARY, JSONObject().put("rich_text", rt(text.take(1900))))
            else notes += "摘要没生成：" + (sum.exceptionOrNull()?.message ?: "返回为空").take(80)
        }
        return notes
    }
    /** blocks() 末尾追加的「#标签1 #标签2」段落 */
    fun isTagBlock(b: JSONObject, tags: List<String>): Boolean {
        if (tags.isEmpty() || b.optString("type") != "paragraph") return false
        val rt = b.optJSONObject("paragraph")?.optJSONArray("rich_text") ?: return false
        val text = (0 until rt.length()).joinToString("") { rt.optJSONObject(it)?.optJSONObject("text")?.optString("content").orEmpty() }
        return text == tags.joinToString(" ") { "#$it" }
    }
    fun publishFull(token: String, type: String, rawTarget: String, titleProp: String, tagsProp: String, title: String, bl: List<JSONObject>, tags: List<String>, cover: CoverSource?, meta: Meta = Meta()): Result {
        val tgt = resolve(token, type, rawTarget, titleProp)
        val props = JSONObject().put(tgt.titleProp, JSONObject().put("title", rt(title)))
        if (tgt.type == "database" && tagsProp.isNotBlank() && tagsProp != PROP_TAGS && tags.isNotEmpty())
            props.put(tagsProp, JSONObject().put("multi_select", JSONArray().apply { tags.forEach { put(JSONObject().put("name", it.replace(",", " "))) } }))
        val propNotes = if (tgt.type == "database") fillProps(props, tgt.schema, tags, meta) else emptyList()
        // 1.15.1：数据库目标的标签已写进「主题标签」属性，正文末尾不再追加 #标签 段落；页面目标没有属性，保留
        val children = if (tgt.type == "database") bl.filterNot { isTagBlock(it, tags) } else bl
        val req = JSONObject().put("parent", tgt.parentJson).put("properties", props).put("children", JSONArray(children.take(100)))
        var (code, txt) = call(token, "POST", "/pages", req.toString())
        var extra = propNotes
        // 属性值被拒（例如选项不允许新建）→ 去掉 1.15 新属性再发一次，保证文章先发出去
        if (code == 400 && tgt.type == "database" && listOf(PROP_CATEGORY, PROP_TAGS, PROP_SUMMARY).any { props.has(it) }) {
            val msg = runCatching { JSONObject(txt).optString("message") }.getOrDefault("").take(100)
            listOf(PROP_CATEGORY, PROP_TAGS, PROP_SUMMARY).forEach { props.remove(it) }
            val r2 = call(token, "POST", "/pages", req.put("properties", props).toString()); code = r2.first; txt = r2.second
            extra = extra + "分类/标签/摘要没写进去：$msg"
        }
        if (code !in 200..299) throw fail(type, "POST", "/pages", code, txt)
        val page = JSONObject(txt)
        bl.drop(100).chunked(100).forEach { chunk ->
            val path = "/blocks/${page.optString("id")}/children"
            val (c2, t2) = call(token, "PATCH", path, JSONObject().put("children", JSONArray(chunk)).toString())
            if (c2 !in 200..299) throw fail(type, "PATCH", path, c2, t2, "页面已创建，但后半段正文追加失败。")
        }
        val note = if (cover != null && page.optString("id").isNotBlank()) setCover(token, page.optString("id"), cover) else ""
        return Result(page.optString("url"), (listOf(note) + extra).filter { it.isNotBlank() }.joinToString("；"))
    }
    suspend fun test(ctx: Context): String = withContext(Dispatchers.IO) {
        val token = Secure.get(ctx, "notion_token"); val s = Settings(ctx)
        if (token.isBlank()) throw ConfigError("请先填写 Notion 集成 Token")
        testWith(token, s.notionType, s.notionTarget)
    }
    fun testWith(token: String, type: String, rawTarget: String): String {
        val (c, t) = call(token, "GET", "/users/me")
        if (c !in 200..299) throw fail(type, "GET", "/users/me", c, t, "Token 无效或已失效。")
        if (rawTarget.isBlank()) return "Token 有效（还没填目标）"
        val tgt = resolve(token, type, rawTarget)
        return if (tgt.type == "page") "连接正常：能访问页面 ${tgt.id}"
        else "连接正常：能访问数据库 ${tgt.id}（data source ${tgt.parentJson.optString("data_source_id")}）"
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
    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String = withContext(Dispatchers.IO) {
        val s = Settings(ctx); val token = Secure.get(ctx, "yuque_token")
        if (token.isBlank() || s.yuqueNs.isBlank()) throw ConfigError("还没有配置语雀。请到「设定 → 发布渠道 → 语雀」填个人 Token 并选择知识库。")
        val md = Export.markdown(title, body, tags, pics, Settings(ctx).showCredit).substringAfter("\n").trim()
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
    suspend fun upload(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String = withContext(Dispatchers.IO) {
        val s = Settings(ctx)
        var token = Secure.get(ctx, "telegraph_token")
        if (token.isBlank()) {
            val (c, t) = Http.call("POST", "$API/createAccount", emptyMap(), JSONObject().put("short_name", "luobi").put("author_name", s.telegraphName).toString())
            val j = JSONObject(t.ifBlank { "{}" })
            if (c !in 200..299 || !j.optBoolean("ok")) throw PublishError("创建 Telegraph 账号失败：" + j.optString("error").ifBlank { Http.msg(c, t) })
            token = j.getJSONObject("result").getString("access_token"); Secure.set(ctx, "telegraph_token", token)
        }
        val ps = Export.paras(body)
        val nodes = JSONArray()
        val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val show = s.showCredit
        fun inl(t: String) = JSONArray().apply { Md.spans(t).forEach { (x, b) -> put(if (b) JSONObject().put("tag", "strong").put("children", JSONArray().put(x)) else x) } }
        ps.forEachIndexed { i, p ->
            val m = Md.line(p, ps.size)
            nodes.put(when (m.kind) {
                MdKind.H2 -> JSONObject().put("tag", "h3").put("children", inl(m.text))
                MdKind.H3 -> JSONObject().put("tag", "h4").put("children", inl(m.text))
                MdKind.QUOTE -> JSONObject().put("tag", "blockquote").put("children", inl(m.text))
                MdKind.BULLET -> JSONObject().put("tag", "ul").put("children", JSONArray().put(JSONObject().put("tag", "li").put("children", inl(m.text))))
                MdKind.NUM -> JSONObject().put("tag", "p").put("children", inl(m.num + ". " + m.text))
                MdKind.HR -> JSONObject().put("tag", "hr")
                MdKind.P -> JSONObject().put("tag", "p").put("children", inl(m.text))
            })
            bp[i]?.forEach { pic -> nodes.put(JSONObject().put("tag", "figure").put("children", JSONArray().put(JSONObject().put("tag", "img").put("attrs", JSONObject().put("src", pic.url)))
                .apply { val cap = pic.captionLine(show); if (cap.isNotBlank()) put(JSONObject().put("tag", "figcaption").put("children", JSONArray().put(cap))) })) } }
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
    fun body(ch: Channel, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList(), show: Boolean = true): String {
        val content = when (ch.format) { "html" -> Export.htmlBody(body, tags, pics = pics, showCredit = show); "markdown" -> Export.markdown(title, body, tags, pics, show).substringAfter("\n").trim(); else -> Md.plainBody(body) }
        return Export.jsonTpl(ch.bodyTpl, title, content, tags)
    }
    private fun headers(ctx: Context, ch: Channel): Map<String, String> {
        val secret = Secure.get(ctx, "ch_${ch.id}")
        return if (secret.isNotBlank() && ch.authHeader.isNotBlank()) mapOf(ch.authHeader to ch.authPrefix + secret) else emptyMap()
    }
    suspend fun publish(ctx: Context, ch: Channel, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String = withContext(Dispatchers.IO) {
        if (ch.url.isBlank()) throw ConfigError("「${ch.name}」还没有填接口地址")
        val (c, t) = Http.call(ch.method, ch.url, headers(ctx, ch), body(ch, title, body, tags, pics, Settings(ctx).showCredit))
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
    fun copyHtml(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val html = "<h1>${Export.esc(title)}</h1>\n" + Export.htmlBody(body, tags, pics = pics, showCredit = Settings(ctx).showCredit)
        cm.setPrimaryClip(ClipData.newHtmlText(title, Export.plain(title, Md.plainBody(body), tags), html))
    }
    /** 存到「下载/落笔」，返回文件名 */
    fun saveHtml(ctx: Context, title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String {
        val name = title.replace(Regex("[\\\\/:*?\"<>|\\n]"), "").take(40).ifBlank { "文章" } + "_" + System.currentTimeMillis() % 100000 + ".html"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/html")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/落笔")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw PublishError("无法在「下载」里创建文件")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(Export.htmlDoc(title, body, tags, pics, Settings(ctx).showCredit).toByteArray()) } ?: throw PublishError("无法写入文件")
        return name
    }
    /** 每个 Uri 恰好一项（去重），用于授予读权限 */
    fun clipOf(uris: List<android.net.Uri>): ClipData { val u = uris.distinct(); return ClipData.newRawUri("", u[0]).apply { u.drop(1).forEach { addItem(ClipData.Item(it)) } } }
    val socialApps = listOf("微信" to "com.tencent.mm", "QQ" to "com.tencent.mobileqq", "微博" to "com.sina.weibo", "抖音" to "com.ss.android.ugc.aweme")
    /** 打开某个 APP；没装返回 false */
    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }
    /** 用相册 / 图片查看器打开刚生成的图 */
    fun view(ctx: Context, uri: android.net.Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { toast(ctx, "没有能打开图片的应用，长图在相册「落笔」里") }
    }
    /** 指定包名发送纯文字（ACTION_SEND text/plain）；没装或失败时弹系统菜单 */
    fun sendTextTo(ctx: Context, pkg: String?, text: String) {
        val it = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        val x = if (pkg != null) Intent(it).setPackage(pkg) else null
        val ok = x != null && ctx.packageManager.queryIntentActivities(x, 0).isNotEmpty()
        if (pkg != null && !ok) toast(ctx, "未检测到该 APP，请选择分享方式")
        try { ctx.startActivity((if (ok) x!! else Intent.createChooser(it, "分享到")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Share.safeStart(ctx, Intent.createChooser(it, "分享到")) }
    }
    /** 指定包名分享图片；失败时弹系统菜单。单图只放 EXTRA_STREAM + 同一 ClipData 项，不重复 */
    fun sendImagesTo(ctx: Context, pkg: String?, uris: ArrayList<android.net.Uri>, text: String) {
        if (uris.isEmpty()) return
        val it = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]) else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)).apply {
            type = "image/jpeg"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = Out.clipOf(uris)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
        }
        val x = if (pkg != null) Intent(it).setPackage(pkg) else null
        val ok = x != null && ctx.packageManager.queryIntentActivities(x, 0).isNotEmpty()
        if (pkg != null && !ok) toast(ctx, "未检测到该 APP，请选择分享方式")
        try { ctx.startActivity((if (ok) x!! else Intent.createChooser(it, "分享到")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Share.safeStart(ctx, Intent.createChooser(it, "分享到")) }
    }
}
