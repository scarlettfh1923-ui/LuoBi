package com.scarlett.luobi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 1.11–1.12 事实校对。
 * 搜索：优先 DeepSeek 官方 Search API（与写作同一把 Key）→ 失败或没 Key 时退回中文维基百科（免 Key）。
 * 核对：硬事实 only（片名/书名、情节与年代、作者/导演/演员、引语出处、可核验事件）；不做语法文笔。
 */
data class FactHit(val title: String, val url: String, val snippet: String, val source: String)
data class FactIssue(
    val id: String,
    val span: String,
    val category: String,
    val severity: String, // 高 / 中 / 低
    val claim: String,
    val correction: String,
    val source: String,
    val note: String = "",
    var selected: Boolean = true,
    /** AI 原始建议；手改后 correction 可能不同 */
    val aiCorrection: String = "",
    /** 已成功应用到正文 */
    var applied: Boolean = false,
)
data class FactReport(
    val issues: List<FactIssue>,
    val skipped: List<String>,
    val searchNote: String,
    val queries: List<String>,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    /** 未应用的问题数（角标） */
    fun pendingCount() = issues.count { !it.applied && it.span.isNotBlank() }
}

object FactCheck {
    const val DEFAULT_PROMPT = """你是事实校对编辑。只查找硬事实错误，不做语法、文笔、结构或润色建议。

核对范围：
1. 片名 / 书名 / 作品名是否写对；
2. 情节、年代、人物关系是否属实；
3. 作者、导演、演员等姓名是否张冠李戴；
4. 引语、出处、事件、数据是否可核验。
评价性句子（「最动人」「很精彩」等）不是事实，放进 skipped 并跳过。

下面是搜索到的参考资料（标题｜摘要｜来源｜链接）：
{搜索结果}

文章标题：{标题}
文章正文：
<<<
{正文}
>>>

只输出一个 JSON 对象，不要解释：
{"issues":[
  {"span":"原文中的原句或短语（必须能在正文里找到）",
   "category":"片名|情节|作者名|来源事实|其他",
   "severity":"高|中|低",
   "claim":"文章里的说法",
   "correction":"正确说法（可直接替换 span 的短句）",
   "source":"依据来源名称",
   "note":"可选说明"}
],"skipped":["跳过的评价性句子…"]}"""

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 LuoBi/1.12"
    private val nameLike = Regex("[《「\"“]([^》」\"”]{1,40})[》」\"”]|([\\p{IsHan}]{2,8}(?:电影|电视剧|小说|导演|主演|作者))")
    private val yearLike = Regex("(19|20)\\d{2}年")

    /** 从正文抽出适合搜索的短查询（片名、人名、带年份的句子片段） */
    fun queries(title: String, body: String, max: Int = 6): List<String> {
        val text = title + "\n" + body
        val out = LinkedHashSet<String>()
        nameLike.findAll(text).forEach { m -> out += (m.groupValues[1].ifBlank { m.groupValues[2] }).take(40) }
        yearLike.findAll(text).forEach { m ->
            val i = m.range.first; val start = (i - 12).coerceAtLeast(0); val end = (m.range.last + 16).coerceAtMost(text.length)
            out += text.substring(start, end).replace(Regex("\\s+"), " ").trim().take(40)
        }
        // 带「导演/主演/作者是」的短句
        Regex("[^。！？\\n]{0,20}(?:导演|主演|作者|编剧|出品)[^。！？\\n]{2,30}").findAll(text).forEach { out += it.value.trim().take(40) }
        if (out.isEmpty() && title.isNotBlank()) out += title.take(40)
        return out.filter { it.length in 2..40 }.take(max)
    }

    // ---------- DeepSeek Search（官方 /v1/tools/search，与 chat 同一 Key） ----------
    fun deepseekSearch(key: String, query: String, count: Int = 5): List<FactHit> {
        val body = JSONObject().put("query", query.take(128)).put("count", count).put("need_content", false)
        val c = URL("https://api.deepseek.com/v1/tools/search").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 25000
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (code !in 200..299) throw java.io.IOException("DeepSeek Search HTTP $code: ${txt.take(120)}")
        val arr = JSONObject(txt).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            FactHit(it.optString("title"), it.optString("url"),
                it.optString("summary").ifBlank { it.optString("snippet") }.take(500),
                it.optString("site_name").ifBlank { "网页" })
        }
    }

    // ---------- 维基百科（免 Key 兜底） ----------
    fun wikiSearch(query: String, limit: Int = 3): List<FactHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=$limit&format=json&utf8=1"
        val j = getJson(searchUrl)
        val arr = j.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val title = o.optString("title")
            val snip = o.optString("snippet").replace(Regex("<[^>]+>"), "")
            FactHit(title, "https://zh.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"), snip, "维基百科")
        }
    }
    fun wikiSummary(title: String): FactHit? {
        val enc = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        return runCatching {
            val j = getJson("https://zh.wikipedia.org/api/rest_v1/page/summary/$enc")
            val t = j.optString("title"); val extract = j.optString("extract")
            if (t.isBlank() || extract.isBlank()) null
            else FactHit(t, j.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty().ifBlank { "https://zh.wikipedia.org/wiki/$enc" }, extract.take(600), "维基百科")
        }.getOrNull()
    }
    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    /** 可选检索源（设定里选；失败时按序兜底） */
    enum class SearchProvider(val id: String, val label: String) {
        DEEPSEEK("deepseek", "DeepSeek（推荐）"), WIKI("wiki", "维基百科"), BOCHA("bocha", "博查");
        companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: DEEPSEEK }
    }

    fun bochaSearch(key: String, query: String, count: Int = 5): List<FactHit> {
        val body = JSONObject().put("query", query).put("count", count).put("summary", true).put("freshness", "noLimit")
        val c = URL("https://api.bochaai.com/v1/web-search").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 12000; c.readTimeout = 25000
        c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (code !in 200..299) throw java.io.IOException("博查 HTTP $code: ${txt.take(120)}")
        val j = JSONObject(txt)
        val data = j.optJSONObject("data") ?: j
        val arr = data.optJSONArray("webPages")?.let { it.optJSONObject(0)?.optJSONArray("value") }
            ?: data.optJSONObject("webPages")?.optJSONArray("value")
            ?: data.optJSONArray("results")
            ?: data.optJSONArray("data")
            ?: JSONArray()
        // 兼容 Bocha 文档结构：data.webPages.value
        val pages = data.optJSONObject("webPages")?.optJSONArray("value") ?: arr
        return (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.map {
            FactHit(it.optString("name").ifBlank { it.optString("title") },
                it.optString("url").ifBlank { it.optString("displayUrl") },
                it.optString("summary").ifBlank { it.optString("snippet") }.take(500),
                it.optString("siteName").ifBlank { "网页" })
        }
    }

    private fun wikiHits(queries: List<String>): List<FactHit> {
        val hits = LinkedHashMap<String, FactHit>()
        queries.forEach { q ->
            wikiSearch(q).forEach { hits.putIfAbsent(it.url.ifBlank { it.title }, it) }
            wikiSummary(q)?.let { hits.putIfAbsent(it.url, it) }
            wikiSearch(q, 1).firstOrNull()?.title?.let { wikiSummary(it) }?.let { hits.putIfAbsent(it.url, it) }
        }
        return hits.values.toList()
    }

    /**
     * 按她在设定里选的检索源搜索；失败（没 Key / 网络错）时按 DeepSeek → 维基 → 博查 的顺序温柔兜底，并在 note 里说明。
     */
    suspend fun searchAll(ctx: Context, queries: List<String>): Pair<List<FactHit>, String> = withContext(Dispatchers.IO) {
        val s = Settings(ctx)
        val prefer = SearchProvider.of(s.factSearch)
        val order = listOf(prefer) + SearchProvider.entries.filter { it != prefer }
        val notes = mutableListOf<String>()
        for (p in order) {
            val r = runCatching {
                when (p) {
                    SearchProvider.DEEPSEEK -> {
                        val key = s.key("deepseek")
                        if (key.isBlank()) error("还没填 DeepSeek Key")
                        val web = coroutineScope {
                            queries.map { q -> async { deepseekSearch(key, "$q lang:zh,en") } }.awaitAll().flatten()
                        }
                        web + wikiHits(queries.take(2))
                    }
                    SearchProvider.WIKI -> wikiHits(queries)
                    SearchProvider.BOCHA -> {
                        val key = s.bochaKey
                        if (key.isBlank()) error("还没填博查 Key")
                        coroutineScope { queries.map { q -> async { bochaSearch(key, q) } }.awaitAll().flatten() }
                    }
                }
            }
            if (r.isSuccess) {
                val hits = r.getOrDefault(emptyList())
                if (hits.isNotEmpty() || p == SearchProvider.WIKI) {
                    val used = "检索：${p.label}"
                    val note = if (notes.isEmpty()) used else used + "（已跳过：" + notes.joinToString("；") + "）"
                    val all = LinkedHashMap<String, FactHit>()
                    hits.forEach { all.putIfAbsent(it.url.ifBlank { it.title }, it) }
                    return@withContext all.values.toList() to note
                }
                notes += "${p.label}没有结果"
            } else {
                notes += "${p.label}不可用：${r.exceptionOrNull()?.message?.take(40) ?: "失败"}"
            }
        }
        emptyList<FactHit>() to ("所有检索源都没拿到结果" + if (notes.isNotEmpty()) "（${notes.joinToString("；")}）" else "")
    }

    fun parse(reply: String): Pair<List<FactIssue>, List<String>> {
        val j = CleanParse.lenient(reply) ?: throw CleanParse.BadFormat(reply.take(300))
        val issues = j.optJSONArray("issues") ?: j.optJSONArray("事实问题") ?: JSONArray()
        val skipped = j.optJSONArray("skipped") ?: j.optJSONArray("跳过") ?: JSONArray()
        val list = (0 until issues.length()).mapNotNull { i ->
            val o = issues.optJSONObject(i) ?: return@mapNotNull null
            fun g(vararg k: String) = k.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: ""
            val span = g("span", "原文", "句子"); val correction = g("correction", "正确", "改正")
            if (span.isBlank() && correction.isBlank()) return@mapNotNull null
            FactIssue(
                id = "$i-${span.hashCode()}",
                span = span.ifBlank { g("claim", "说法") },
                category = g("category", "类别").ifBlank { "其他" },
                severity = g("severity", "级别").let { if (it in listOf("高", "中", "低")) it else "中" },
                claim = g("claim", "说法"),
                correction = correction,
                source = g("source", "来源"),
                note = g("note", "说明"),
                aiCorrection = correction,
                selected = true,
                applied = false,
            )
        }
        val skip = (0 until skipped.length()).map { skipped.optString(it) }.filter { it.isNotBlank() }
        return list to skip
    }

    /** 把 correction 替换进正文（只替换第一次出现的 span） */
    fun applyFixes(body: String, issues: List<FactIssue>): String {
        var out = body
        issues.filter { it.selected && !it.applied && it.span.isNotBlank() && it.correction.isNotBlank() }
            .sortedByDescending { it.span.length }
            .forEach { iss -> if (out.contains(iss.span)) out = out.replaceFirst(iss.span, iss.correction) }
        return out
    }

    /** 应用后：勾选且改正成功的标 applied；保留未勾选/未改成功的项与手改文案 */
    fun afterApply(report: FactReport, appliedIds: Set<String>): FactReport {
        val issues = report.issues.map { iss ->
            if (iss.id in appliedIds) iss.copy(applied = true, selected = false) else iss
        }
        return report.copy(issues = issues)
    }

    fun encodeReport(r: FactReport): String {
        val issues = JSONArray()
        r.issues.forEach { iss ->
            issues.put(JSONObject()
                .put("id", iss.id)
                .put("span", iss.span)
                .put("category", iss.category)
                .put("severity", iss.severity)
                .put("claim", iss.claim)
                .put("correction", iss.correction)
                .put("aiCorrection", iss.aiCorrection.ifBlank { iss.correction })
                .put("source", iss.source)
                .put("note", iss.note)
                .put("selected", iss.selected)
                .put("applied", iss.applied))
        }
        val skipped = JSONArray(); r.skipped.forEach { skipped.put(it) }
        val queries = JSONArray(); r.queries.forEach { queries.put(it) }
        return JSONObject()
            .put("issues", issues)
            .put("skipped", skipped)
            .put("searchNote", r.searchNote)
            .put("queries", queries)
            .put("checkedAt", r.checkedAt)
            .toString()
    }

    fun decodeReport(json: String): FactReport? {
        if (json.isBlank()) return null
        return runCatching {
            val j = JSONObject(json)
            val arr = j.optJSONArray("issues") ?: JSONArray()
            val issues = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                fun g(vararg k: String) = k.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: ""
                val correction = g("correction", "改正")
                val ai = g("aiCorrection").ifBlank { correction }
                FactIssue(
                    id = g("id").ifBlank { "$i-${g("span").hashCode()}" },
                    span = g("span", "原文"),
                    category = g("category", "类别").ifBlank { "其他" },
                    severity = g("severity", "级别").let { if (it in listOf("高", "中", "低")) it else "中" },
                    claim = g("claim", "说法"),
                    correction = correction,
                    source = g("source", "来源"),
                    note = g("note", "说明"),
                    selected = if (o.has("selected")) o.optBoolean("selected", true) else true,
                    aiCorrection = ai,
                    applied = o.optBoolean("applied", false),
                )
            }
            val skippedArr = j.optJSONArray("skipped") ?: JSONArray()
            val skipped = (0 until skippedArr.length()).map { skippedArr.optString(it) }.filter { it.isNotBlank() }
            val qArr = j.optJSONArray("queries") ?: JSONArray()
            val queries = (0 until qArr.length()).map { qArr.optString(it) }.filter { it.isNotBlank() }
            FactReport(issues, skipped, j.optString("searchNote"), queries, j.optLong("checkedAt", System.currentTimeMillis()))
        }.getOrNull()
    }

    fun buildPrompt(tpl: String, title: String, body: String, hits: List<FactHit>): String = tpl
        .replace("{搜索结果}", hits.joinToString("\n") { "- ${it.title}｜${it.snippet.take(280)}｜${it.source}｜${it.url}" }.ifBlank { "（没有搜到参考资料）" })
        .replace("{标题}", title)
        .replace("{正文}", body.take(12000))

    /**
     * 选定校对用的聊天模型（与「事实检索」独立）。
     * - factModel 非空且已配置 → 用她在设定里选的
     * - 否则尽量对齐这篇文章的写作模型（Note.model 是「名称 · 型号」展示串，不是 ref）
     * - 再否则用默认写作模型 / DeepSeek / 第一个已配置
     * 绝不「故意换一家」——1.11.0 的 prefer-different 会误选 gpt-5.5 导致 403。
     */
    fun resolveCheckModel(s: Settings, writerDisplay: String = ""): ModelRef {
        val cfg = s.configured()
        if (cfg.isEmpty()) throw ConfigError("还没有可用的模型。请到「设定 → 模型与密钥」填写 Key。")
        fun byRef(ref: String) = cfg.firstOrNull { it.ref == ref }
        s.factModel.takeIf { it.isNotBlank() }?.let { byRef(it) }?.let { return it }
        // Note.model 形如 "DeepSeek · deepseek-flash" 或 "OpenAI · gpt-5.5"
        val disp = writerDisplay.trim()
        if (disp.isNotBlank()) {
            cfg.firstOrNull { it.label == disp || "${it.label} · ${it.sub}" == disp || disp.startsWith(it.label) && disp.contains(it.sub) }?.let { return it }
            cfg.firstOrNull { disp.contains(it.sub) && it.sub.isNotBlank() }?.let { return it }
        }
        s.defaultModel.takeIf { it.isNotBlank() }?.let { byRef(it) }?.let { return it }
        byRef("p:deepseek")?.let { return it }
        return cfg.first()
    }

    /**
     * 完整流程：抽查询 → 按「事实检索」搜索 → 用「校对模型」做二次聊天调用 → 解析。
     * writerDisplay = 文章上记下的模型展示名（可空）。
     */
    suspend fun run(ctx: Context, title: String, body: String, writerDisplay: String = ""): FactReport = withContext(Dispatchers.IO) {
        val s = Settings(ctx)
        val qs = queries(title, body)
        val (hits, note) = searchAll(ctx, qs)
        val prompt = buildPrompt(s.factPrompt, title, body, hits)
        val check = resolveCheckModel(s, writerDisplay)
        val target = Ai.target(ctx, check.ref)
        val reply = try {
            Ai.chatText(target, "你是事实校对编辑。只输出一个合法 JSON 对象。", prompt, true)
        } catch (e: ApiError) {
            throw refineFactModelError(e, check) ?: e
        }
        val (issues, skipped) = parse(reply)
        val ok = issues.filter { it.span.isBlank() || body.contains(it.span) || title.contains(it.span) }
        FactReport(ok, skipped, note + " · 校对模型 ${check.label} · ${check.sub}", qs)
    }

    /** 403/模型无权时给出可操作的中文提示（点名校对模型） */
    fun refineFactModelError(e: ApiError, model: ModelRef): ConfigError? {
        if (e.code != 403 && e.code != 401) return null
        val raw = (e.raw + " " + Errors.providerMessage(e.raw)).lowercase()
        val mentionsModel = raw.contains("model") || raw.contains("gpt-") || raw.contains("deepseek") ||
            raw.contains(model.sub.lowercase()) || raw.contains("not authorized") || raw.contains("无权") ||
            raw.contains("permission") || raw.contains("does not have access") || raw.contains("token_not_authorized") ||
            raw.contains("not authorized for model")
        if (e.code == 403 || mentionsModel) {
            return ConfigError("校对模型「${model.label} · ${model.sub}」无权访问（HTTP ${e.code}）。请到「设定 → 事实校对 → 校对模型」改成你有权限的模型（例如 DeepSeek）。")
        }
        return null
    }
}
