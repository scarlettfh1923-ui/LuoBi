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

    /** code = 腾讯云错误码（如 InvalidParameter.xxx），raw = 服务端原始提示（不含 Key） */
    class Err(val code: String, val raw: String = "") : java.io.IOException(plain(code, raw))
    data class Page(val title: String, val url: String, val date: String, val passage: String, val content: String, val site: String, val score: Double, val authority: Int)
    /** 这个账号的版本不支持 Site / Industry 时，本次运行后面都只发 Query */
    @Volatile var bareOnly = false

    private var session: String? = null
    private var nextId = 1

    /** 错误码 → 人话：只有 UnauthorizedOperation 才说 Key 不对；其它一律带上真实错误码和原始提示 */
    fun plain(code: String, raw: String = ""): String {
        val tail = " [$code" + (raw.trim().take(120).takeIf { it.isNotBlank() }?.let { "：$it" } ?: "") + "]"
        return when {
            code.startsWith("UnauthorizedOperation") -> "元宝搜索 Key 不对（或已被删除）$tail"
            code.startsWith("ResourceNotFound") -> "元宝搜索服务未开通（开通后要等几分钟才生效）$tail"
            code.startsWith("ResourceUnavailable") -> "元宝搜索账号欠费，服务暂不可用$tail"
            code.startsWith("RequestLimitExceeded") || code == "HTTP429" -> "元宝搜索请求太频繁，被暂时限流$tail"
            code.startsWith("InvalidParameter") -> "元宝搜索参数不被支持$tail"
            else -> "元宝搜索出错$tail"
        }
    }

    /** 从任意错误文本里取出错误码和提示：{"Code":"X","Message":"Y"} / [X] Y / 已知码 */
    fun parseErr(text: String, http: Int): Err {
        val code = Regex("\"Code\"\\s*:\\s*\"([A-Za-z.]+)\"").find(text)?.groupValues?.get(1)
            ?: Regex("\\[?([A-Z][a-zA-Z]+(?:\\.[A-Za-z]+)?)\\]?").findAll(text).map { it.groupValues[1] }.firstOrNull { c -> codes.any { c.startsWith(it) } }
            ?: if (http !in 200..299) "HTTP$http" else "Unknown"
        val msg = Regex("\"Message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            ?: Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            ?: text.replace(Regex("\\s+"), " ").take(120)
        return Err(code, msg.replace(Regex("Bearer\\s+\\S+"), "Bearer ***"))
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
        if (e != null) throw parseErr(e.toString(), r.code)
        if (r.code !in 200..299) throw parseErr(r.body, r.code)
    }
    private val codes = listOf("UnauthorizedOperation", "ResourceNotFound", "ResourceUnavailable", "RequestLimitExceeded", "InvalidParameter", "InternalError", "FailedOperation", "LimitExceeded", "AuthFailure", "MissingParameter", "UnsupportedOperation")

    /**
     * SearchPro。site / industry 可空；Cnt / Mode 从不发送（极速版不支持）。
     * 带了可选参数却返回 InvalidParameter / UnauthorizedOperation / UnsupportedOperation 时，去掉可选参数原样重试一次，
     * 并在本次运行里改为只发 Query（不同版本支持的参数不同）。RequestLimitExceeded 退避后重试。
     */
    fun search(key: String, query: String, site: String = "", industry: String = "", ctx: Context? = null): List<Page> {
        val extras = !bareOnly && (site.isNotBlank() || industry.isNotBlank())
        return try { searchOnce(key, query, if (extras) site else "", if (extras) industry else "", ctx) }
        catch (e: Err) {
            val paramLike = e.code.startsWith("InvalidParameter") || e.code.startsWith("UnauthorizedOperation") || e.code.startsWith("UnsupportedOperation")
            if (extras && paramLike) { bareOnly = true; searchOnce(key, query, "", "", ctx) } else throw e
        }
    }

    private fun searchOnce(key: String, query: String, site: String, industry: String, ctx: Context?): List<Page> {
        var attempt = 0
        while (true) {
            try { return callSearch(key, query, site, industry, ctx) }
            catch (e: Err) {
                if (!e.code.startsWith("RequestLimitExceeded") || attempt >= 2) throw e
                FactNet.sleep(1000L shl attempt); attempt++
            }
        }
    }

    private fun callSearch(key: String, query: String, site: String, industry: String, ctx: Context?): List<Page> {
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
        if (res.optBoolean("isError")) throw parseErr(text, r.code)
        val payload = res.optJSONObject("structuredContent") ?: runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val body = payload.optJSONObject("Response") ?: payload
        body.optJSONObject("Error")?.let { throw Err(it.optString("Code").ifBlank { "Unknown" }, it.optString("Message")) }
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

    /** 测试连接也走「带站点参数」的真实路径，避免按钮通过、校对失败 */
    fun testReal(ctx: Context, key: String): String = try {
        bareOnly = false
        val a = search(key, "百年孤独 开头", site = "baike.baidu.com", ctx = ctx).size
        "连接成功，返回 $a 条结果" + if (bareOnly) "（当前版本不支持站点 / 领域参数，已自动改为普通搜索）" else ""
    } catch (e: Err) { e.message ?: "连接失败" } catch (e: Exception) { "连接失败：网络不可用" }

    fun resetSession() { session = null; bareOnly = false }

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
