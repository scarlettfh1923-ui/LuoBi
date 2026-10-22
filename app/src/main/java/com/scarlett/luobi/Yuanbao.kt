package com.scarlett.luobi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.16：腾讯元宝搜索（WSA）作为事实检索源。
 * 走官方 MCP：POST https://api.wsa.cloud.tencent.com/Mcp，JSON-RPC 2.0（Streamable HTTP），
 * 请求头 Authorization: Bearer <WSA_API_KEY>，唯一工具 SearchPro。不用 SecretId/SecretKey 签名。
 * - 先直接 tools/call；若服务端要求会话（返回需要 initialize / 缺 Mcp-Session-Id），则 initialize → notifications/initialized → 重试。
 * - 响应可能是 application/json，也可能是 text/event-stream（data: 行），两种都解析。
 * - Key 只放请求头，绝不写日志 / 报错文案。
 */
object Yuanbao {
    const val URL = "https://api.wsa.cloud.tencent.com/Mcp"
    const val PROTOCOL = "2025-06-18"
    const val FREE_PER_MONTH = 2000
    /** 每篇文章最多打几次元宝（约 6–8） */
    const val BUDGET_PER_ARTICLE = 8

    class Err(val code: String, msg: String) : java.io.IOException(msg)
    data class Page(val title: String, val url: String, val date: String, val passage: String, val content: String, val site: String, val score: Double, val authority: Int)

    private var session: String? = null
    private var nextId = 1

    /** 错误码 → 人话 */
    fun plain(code: String): String = when {
        code.contains("UnauthorizedOperation") -> "元宝搜索 Key 不对（或已被删除），请到元宝搜索控制台检查"
        code.contains("ResourceNotFound") -> "元宝搜索服务未开通（开通后要等几分钟才生效）"
        code.contains("ResourceUnavailable") -> "元宝搜索账号欠费，服务暂不可用"
        code.contains("RequestLimitExceeded") || code == "429" -> "元宝搜索请求太频繁，被暂时限流"
        code.contains("InvalidParameter") -> "元宝搜索参数不对"
        code == "401" || code == "403" -> "元宝搜索 Key 不对"
        else -> "元宝搜索暂时不可用"
    }

    private fun headers(key: String) = buildMap {
        put("Authorization", "Bearer $key"); put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream"); put("MCP-Protocol-Version", PROTOCOL)
        session?.let { put("Mcp-Session-Id", it) }
    }

    /** 解析 JSON 或 SSE（取最后一个带 result/error 的 data 帧） */
    fun parseBody(body: String): JSONObject? {
        val t = body.trim()
        if (t.startsWith("{")) return runCatching { JSONObject(t) }.getOrNull()
        var last: JSONObject? = null
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { runCatching { JSONObject(buf.toString()) }.getOrNull()?.let { if (it.has("result") || it.has("error")) last = it }; buf.clear() } }
        for (line in t.lines()) {
            when {
                line.startsWith("data:") -> buf.append(line.removePrefix("data:").trim())
                line.isBlank() -> flush()
            }
        }
        flush()
        return last
    }

    private fun rpc(key: String, method: String, params: JSONObject, notify: Boolean = false): Pair<FactNet.Resp, JSONObject?> {
        val o = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (!notify) o.put("id", nextId++)
        val r = FactNet.call("POST", URL, headers(key), o.toString())
        r.headers.entries.firstOrNull { it.key.equals("Mcp-Session-Id", true) }?.value?.takeIf { it.isNotBlank() }?.let { session = it }
        return r to if (notify) null else parseBody(r.body)
    }

    private fun needsInit(r: FactNet.Resp, j: JSONObject?): Boolean {
        val msg = (j?.optJSONObject("error")?.optString("message").orEmpty() + " " + r.body.take(300)).lowercase()
        return (r.code == 400 || r.code == 404 || j?.optJSONObject("error") != null) && (msg.contains("initializ") || msg.contains("session"))
    }

    fun initialize(key: String) {
        session = null
        val p = JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "LuoBi").put("version", "1.16.0"))
        val (r, j) = rpc(key, "initialize", p)
        checkErr(r, j)
        rpc(key, "notifications/initialized", JSONObject(), notify = true)
    }

    private fun checkErr(r: FactNet.Resp, j: JSONObject?) {
        val e = j?.optJSONObject("error")
        val text = e?.toString() ?: if (r.code !in 200..299) r.body else ""
        if (e != null || r.code !in 200..299) throw Err(codeIn(text).ifBlank { r.code.toString() }, plain(codeIn(text).ifBlank { r.code.toString() }))
    }
    private val codes = listOf("UnauthorizedOperation", "ResourceNotFound", "ResourceUnavailable", "RequestLimitExceeded", "InvalidParameter", "InternalError")
    private fun codeIn(s: String) = codes.firstOrNull { s.contains(it) }.orEmpty()

    /** SearchPro。site / industry 可空；Cnt 不传（极速版不支持） */
    fun search(key: String, query: String, site: String = "", industry: String = "", ctx: Context? = null): List<Page> {
        val args = JSONObject().put("Query", clip(query))
        if (site.isNotBlank()) args.put("Site", site)
        if (industry.isNotBlank()) args.put("Industry", industry)
        val p = JSONObject().put("name", "SearchPro").put("arguments", args)
        ctx?.let { Usage.bump(it) }
        var (r, j) = rpc(key, "tools/call", p)
        if (needsInit(r, j)) { initialize(key); ctx?.let { Usage.bump(it) }; val again = rpc(key, "tools/call", p); r = again.first; j = again.second }
        checkErr(r, j)
        val res = j?.optJSONObject("result") ?: return emptyList()
        val text = res.optJSONArray("content")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") } }.orEmpty()
        if (res.optBoolean("isError")) { val c = codeIn(text); throw Err(c.ifBlank { "error" }, plain(c)) }
        val payload = res.optJSONObject("structuredContent") ?: runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val body = payload.optJSONObject("Response") ?: payload
        body.optJSONObject("Error")?.let { val c = it.optString("Code"); throw Err(c, plain(c)) }
        return pages(body.optJSONArray("Pages") ?: JSONArray())
    }

    fun pages(a: JSONArray): List<Page> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: runCatching { JSONObject(a.optString(i)) }.getOrNull() ?: return@mapNotNull null
        Page(o.optString("title"), o.optString("url"), o.optString("date"), o.optString("passage"), o.optString("content"), o.optString("site"), o.optDouble("score", 0.0), o.optInt("authority_level", 0))
    }.sortedByDescending { it.score + it.authority * 0.2 }

    /** Query ≤ 1500 UTF-8 字节 */
    fun clip(q: String): String { var s = q; while (s.toByteArray(Charsets.UTF_8).size > 1500) s = s.dropLast(1); return s }

    fun toHit(p: Page): FactHit = FactHit(p.title, p.url, p.passage.ifBlank { p.content }.take(500), p.site.ifBlank { "网页" }, "元宝",
        text = listOf(p.passage, p.content).filter { it.isNotBlank() }.distinct().joinToString("\n"), date = p.date)

    /** 测试连接：一次 SearchPro */
    fun test(ctx: Context, key: String): String = try {
        val n = search(key, "柯林伍德 历史的观念", ctx = ctx).size
        "连接成功，返回 $n 条结果"
    } catch (e: Err) { e.message ?: "连接失败" } catch (e: Exception) { "连接失败：网络不可用" }

    fun resetSession() { session = null }

    /** 本机按月计数（不硬拦，接近上限时提醒） */
    object Usage {
        private fun month() = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
        private fun sp(c: Context) = c.getSharedPreferences("yuanbao_usage", Context.MODE_PRIVATE)
        fun used(c: Context): Int = sp(c).let { if (it.getString("m", "") == month()) it.getInt("n", 0) else 0 }
        fun bump(c: Context) { val n = used(c) + 1; sp(c).edit().putString("m", month()).putInt("n", n).apply() }
        fun label(c: Context) = "本月已用 ${used(c)} / $FREE_PER_MONTH"
        fun nearLimit(c: Context) = used(c) >= FREE_PER_MONTH * 9 / 10
    }
}
