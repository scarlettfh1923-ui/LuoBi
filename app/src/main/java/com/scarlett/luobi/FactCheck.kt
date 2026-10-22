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
data class FactHit(val title: String, val url: String, val snippet: String, val source: String,
    /** 来自哪个检索源（DeepSeek / Kimi / 维基 / 博查），用于交叉印证计数 */ val via: String = "",
    /** 1.15.1 情节核对用的全文（已裁剪到相关段落） */ val text: String = "", val date: String = "")
/** 结果里展示的依据链接 */
data class FactLink(val title: String, val url: String, val via: String)
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
    /** 1.15.1：有出入 / 无法核实 / 已印证 */
    val verdict: String = "有出入",
    /** 资料里支持判断的原句 */
    val quote: String = "",
    /** 依据来源（标题 + 链接） */
    val links: List<FactLink> = emptyList(),
    /** 有几个不同检索源印证（0 = 没有资料依据） */
    val corroboration: Int = 0,
    /** 模型引用的证据编号（E1…），用于回填 links */
    val evidenceIds: List<String> = emptyList(),
) {
    /** 「2 个来源印证」/「仅 1 个来源」/「无资料依据」 */
    fun corroborationLabel(): String = when { corroboration >= 2 -> "$corroboration 个来源印证"; corroboration == 1 -> "仅 1 个来源"; else -> "无资料依据" }
}
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

    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36 LuoBi/1.12"
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
        DEEPSEEK("deepseek", "DeepSeek（推荐）"), KIMI("kimi", "Kimi"), WIKI("wiki", "维基百科"), BOCHA("bocha", "博查"), YUANBAO("yuanbao", "元宝搜索");
        val short get() = label.replace("（推荐）", "").let { if (this == DEEPSEEK) "DeepSeek 搜索" else it }
        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id } ?: DEEPSEEK
            /** 1.15.1：多选，存成逗号分隔；空 = 只用 DeepSeek（老行为） */
            fun many(csv: String): List<SearchProvider> = csv.split(',').map { it.trim() }.filter { it.isNotBlank() }
                .mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct().ifEmpty { listOf(DEEPSEEK) }
            fun csv(list: List<SearchProvider>) = entries.filter { it in list }.joinToString(",") { it.id }
        }
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

    /** 1.15.1 检索结果 */
    data class Gathered(val hits: List<FactHit>, val note: String, val queries: List<String>, val modelOnly: Boolean, val fetches: Int, val quotes: List<FactQuotes.Trail> = emptyList())

    suspend fun searchAll(ctx: Context, title: String, body: String): Gathered = withContext(Dispatchers.IO) {
        val s = Settings(ctx)
        gather(SearchProvider.many(s.factSearch), { p -> when (p) { SearchProvider.DEEPSEEK -> s.key("deepseek"); SearchProvider.KIMI -> s.key("kimi"); SearchProvider.BOCHA -> s.bochaKey; SearchProvider.YUANBAO -> s.wsaKey; else -> "" } }, title, body, ctx)
    }

    /**
     * 多源交叉检索（全部串行，FactNet 负责间隔与 429 退避）：
     * 1. 情节项优先：「作品 人物 结局/剧情 剧情简介」定向查询；
     * 2. 每个选中的源跑同一组查询（去重合并后最多 4–5 条）；一个源失败不影响其它源；
     * 3. 情节项抓全文：维基全文、Kimi 搜索带正文、Kimi 抓取权威页（维基/百度百科/豆瓣）；每项 ≤3 次、每次运行 ≤15 次；裁剪到相关段落。
     */
    fun gather(sel: List<SearchProvider>, keyOf: (SearchProvider) -> String, title: String, body: String, ctx: Context? = null): Gathered {
        Yuanbao.resetSession()
        var ybLeft = Yuanbao.BUDGET_PER_ARTICLE
        val plots = FactEvidence.plotItems(title, body)
        val qs = FactEvidence.dedupe(plots.map { FactEvidence.plotQuery(it) } + queries(title, body, 8), FactEvidence.MAX_QUERIES + plots.size.coerceAtMost(1))
        val hits = mutableListOf<FactHit>()
        val fails = LinkedHashMap<SearchProvider, String>()
        val used = mutableListOf<SearchProvider>()
        // 1.16：元宝搜索开着时排第一（中文说法优先走它）
        val order = if (SearchProvider.YUANBAO in sel) listOf(SearchProvider.YUANBAO) + (sel - SearchProvider.YUANBAO) else sel
        for (p in order) {
            val key = keyOf(p)
            if (p != SearchProvider.WIKI && key.isBlank()) { fails[p] = FactNet.plain(p.short, FactNet.MissingKey()); continue }
            var got = 0
            for (q in qs) {
                val r = runCatching {
                    when (p) {
                        SearchProvider.DEEPSEEK -> FactSources.deepseek(key, q)
                        SearchProvider.KIMI -> FactSources.kimiSearch(key, q)
                        SearchProvider.WIKI -> FactSources.wikiSearch(q)
                        SearchProvider.BOCHA -> FactSources.bocha(key, q)
                        SearchProvider.YUANBAO -> if (ybLeft-- > 0) Yuanbao.search(key, q, ctx = ctx).map(Yuanbao::toHit) else emptyList()
                    }
                }
                if (r.isFailure) { if (got == 0) fails[p] = (r.exceptionOrNull() as? Yuanbao.Err)?.message ?: FactNet.plain(p.short, r.exceptionOrNull()); break }  // 重试后仍失败：这个源不再继续打
                r.getOrThrow().forEach { hits += it; got++ }
            }
            if (got > 0) used += p
        }
        // 情节全文
        var budget = FactEvidence.MAX_FETCH_PER_RUN; var fetches = 0
        val kimiKey = if (SearchProvider.KIMI in sel) keyOf(SearchProvider.KIMI) else ""
        val wikiOk = SearchProvider.WIKI in sel || SearchProvider.DEEPSEEK in sel
        for (item in plots) {
            var per = 0
            val keys = listOf(item.work) + item.people + item.sentence.chunked(4).filter { c -> c.all { Character.isIdeographic(it.code) } }.take(6)
            fun take(t: String, u: String, via: String, raw: String) {
                val trimmed = FactEvidence.trim(raw, keys)
                if (trimmed.isNotBlank()) hits += FactHit(t, u, trimmed.take(200), t, via, text = trimmed)
            }
            // 1.16：元宝按站点定向（百度百科 / 豆瓣电影 / 中文维基），取 passage+content 当原文证据
            val ybKey = if (SearchProvider.YUANBAO in sel) keyOf(SearchProvider.YUANBAO) else ""
            if (ybKey.isNotBlank()) for (site in listOf("baike.baidu.com", "movie.douban.com", "zh.wikipedia.org")) {
                if (ybLeft <= 0) break
                ybLeft--
                val r = runCatching { Yuanbao.search(ybKey, FactEvidence.plotQuery(item), site = site, ctx = ctx) }
                r.onFailure { if (SearchProvider.YUANBAO !in fails) fails[SearchProvider.YUANBAO] = it.message ?: "元宝搜索暂时不可用" }
                val got = r.getOrNull().orEmpty().take(2)
                got.forEach { pg -> val h = Yuanbao.toHit(pg); take(h.title, h.url, "元宝", h.text) }
                if (r.isFailure) break
                if (got.isNotEmpty()) break
            }
            if (wikiOk && per < FactEvidence.MAX_FETCH_PER_ITEM && budget > 0) {
                val wt = hits.firstOrNull { it.url.contains("wikipedia.org") && it.title.contains(item.work) }?.title ?: item.work
                runCatching { FactSources.wikiExtract(wt) }.onSuccess { if (it.isNotBlank()) take(wt, "https://zh.wikipedia.org/wiki/" + java.net.URLEncoder.encode(wt, "UTF-8"), "维基", it) }
                per++; budget--; fetches++
            }
            if (kimiKey.isNotBlank() && per < FactEvidence.MAX_FETCH_PER_ITEM && budget > 0) {
                runCatching { FactSources.kimiSearch(kimiKey, FactEvidence.plotQuery(item), includeContent = true) }
                    .onSuccess { r -> r.sortedByDescending { FactEvidence.authority(it.url) }.take(2).forEach { h -> if (h.text.isNotBlank()) take(h.title, h.url, "Kimi", h.text) else hits += h } }
                    .onFailure { if (SearchProvider.KIMI !in fails) fails[SearchProvider.KIMI] = FactNet.plain("Kimi", it) }
                per++; budget--; fetches++
                val have = hits.filter { it.text.isNotBlank() }.map { it.url }.toSet()
                val cand = hits.filter { FactEvidence.authority(it.url) > 0 && it.url !in have && !it.url.contains("wikipedia.org") }
                    .sortedByDescending { FactEvidence.authority(it.url) }.map { it }.distinctBy { it.url }
                for (h in cand) {
                    if (per >= FactEvidence.MAX_FETCH_PER_ITEM || budget <= 0) break
                    runCatching { FactSources.kimiFetch(kimiKey, h.url) }.onSuccess { if (it.isNotBlank()) take(h.title, h.url, h.via.ifBlank { "Kimi" }, it) }
                    per++; budget--; fetches++
                }
            }
        }
        // 1.16：引语出处专门检索（短查询 + 原文语言 + 兜底链）
        val trails = FactQuotes.resolve(FactQuotes.items(title, body), FactQuotes.chain(sel, keyOf, ctx) { ybLeft-- > 0 })
        hits += FactQuotes.evidence(trails)
        // 合并去重（同一链接保留带全文的那条；via 合并用于计数）
        val merged = LinkedHashMap<String, FactHit>()
        for (h in hits) {
            val k = h.url.ifBlank { h.title }
            val old = merged[k]
            merged[k] = if (old == null) h else old.copy(text = old.text.ifBlank { h.text }, via = (old.via.split("、") + h.via).filter { it.isNotBlank() }.distinct().joinToString("、"))
        }
        var total = 0
        val capped = merged.values.map { h -> if (h.text.isBlank()) h else { val room = (FactEvidence.TOTAL_CAP - total).coerceAtLeast(0); total += minOf(room, h.text.length); h.copy(text = h.text.take(room)) } }
        val modelOnly = capped.isEmpty()
        val ybNote = if (ctx != null && SearchProvider.YUANBAO in sel && Yuanbao.Usage.nearLimit(ctx)) " · 元宝搜索" + Yuanbao.Usage.label(ctx) + "，快到免费额度" else ""
        val qNote = ybNote + if (trails.isEmpty()) "" else " · 引语出处 " + trails.count { it.found != null } + "/" + trails.size + " 找到"
        return Gathered(capped, searchNote(sel, used, fails, modelOnly) + qNote, qs + trails.flatMap { FactQuotes.queries(it.item).let { (a, b) -> a + b } }, modelOnly, fetches, trails)
    }

    /** 人话版检索说明：「检索：DeepSeek 搜索、Kimi · 维基百科请求太频繁，被暂时限流；博查未填写 Key」 */
    fun searchNote(sel: List<SearchProvider>, used: List<SearchProvider>, fails: Map<SearchProvider, String>, modelOnly: Boolean): String {
        var prevReason = ""
        val failText = fails.entries.joinToString("；") { (p, msg) ->
            val reason = msg.removePrefix(p.short)
            (if (reason == prevReason) "${p.short}同上" else msg).also { prevReason = reason }
        }
        return when {
            modelOnly -> "没拿到任何资料" + (if (failText.isNotBlank()) "：$failText" else "") + "。本次只凭模型自身知识核对，结论仅供参考"
            failText.isBlank() -> "检索：" + used.joinToString("、") { it.short }
            else -> "检索：" + used.joinToString("、") { it.short } + "（$failText）"
        }
    }

    const val EVIDENCE_RULE = """

【证据规则（系统附加，必须遵守）】
- 参考资料按 [E编号] 给出，带「全文摘录」的是作品页面原文。
- 每条 issue 增加字段："verdict"：「有出入」（资料与文章矛盾）/「无法核实」（资料既不支持也不反驳）/「已印证」（资料明确支持）；"evidence"：引用的资料编号数组，如 ["E2","E5"]；"quote"：从资料里原样摘一句支持你判断的话（必须是资料原文，不得编造或改写）。
- 情节、结局、人物关系与人物命运：文章里每一处这类说法都要逐条列出核对结果；资料找不到依据的一律 verdict="无法核实"，correction 留空，绝不能默认为正确。
- 没有资料依据时不要编造改正；verdict 为「有出入」时 evidence 和 quote 必填。"""

    fun parse(reply: String): Pair<List<FactIssue>, List<String>> {
        val j = CleanParse.lenient(reply) ?: throw CleanParse.BadFormat(reply.take(300))
        val issues = j.optJSONArray("issues") ?: j.optJSONArray("事实问题") ?: JSONArray()
        val skipped = j.optJSONArray("skipped") ?: j.optJSONArray("跳过") ?: JSONArray()
        val list = (0 until issues.length()).mapNotNull { i ->
            val o = issues.optJSONObject(i) ?: return@mapNotNull null
            fun g(vararg k: String) = k.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: ""
            val span = g("span", "原文", "句子"); val correction = g("correction", "正确", "改正")
            if (span.isBlank() && correction.isBlank() && g("claim", "说法").isBlank()) return@mapNotNull null
            val verdict = g("verdict", "结论").let { v -> when { v.contains("核实") -> "无法核实"; v.contains("印证") || v.contains("正确") || v.contains("属实") -> "已印证"; else -> "有出入" } }
            val ev = o.optJSONArray("evidence")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: g("evidence").split(Regex("[,，\\s]+")).filter { it.isNotBlank() }
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
                // 只有「有出入」且有改正文案才默认勾选
                selected = verdict == "有出入" && correction.isNotBlank(),
                applied = false,
                verdict = verdict,
                quote = g("quote", "引文", "依据原文"),
                evidenceIds = ev,
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
                .put("applied", iss.applied)
                .put("verdict", iss.verdict).put("quote", iss.quote).put("corroboration", iss.corroboration)
                .put("links", JSONArray().apply { iss.links.forEach { l -> put(JSONObject().put("title", l.title).put("url", l.url).put("via", l.via)) } }))
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
                    verdict = g("verdict").ifBlank { "有出入" },
                    quote = g("quote"),
                    corroboration = o.optInt("corroboration", 0),
                    links = o.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { FactLink(it.optString("title"), it.optString("url"), it.optString("via")) } } ?: emptyList(),
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
        .replace("{搜索结果}", evidenceBlock(hits).ifBlank { "（没有搜到参考资料：只能凭模型知识判断，情节类一律标「无法核实」）" })
        .replace("{标题}", title)
        .replace("{正文}", body.take(12000)) + EVIDENCE_RULE

    fun evidenceBlock(hits: List<FactHit>): String = hits.mapIndexed { i, h ->
        "[E${i + 1}] ${h.title}｜${h.snippet.take(280)}｜${h.source}｜${h.url}" + if (h.text.isNotBlank()) "\n全文摘录：\n${h.text}" else ""
    }.joinToString("\n")

    /** 用证据编号回填链接与印证数；模型没给编号时，用改正文案在资料里出现的源数兜底 */
    fun attachEvidence(issues: List<FactIssue>, hits: List<FactHit>): List<FactIssue> = issues.map { iss ->
        val cited = iss.evidenceIds.mapNotNull { id -> id.trim().removePrefix("E").removePrefix("[E").trimEnd(']').toIntOrNull()?.let { hits.getOrNull(it - 1) } }
        val support = cited.ifEmpty {
            val needle = iss.correction.takeIf { it.length >= 2 } ?: iss.quote.takeIf { it.length >= 4 }
            if (needle == null) emptyList() else hits.filter { (it.snippet + it.text + it.title).contains(needle) }
        }
        val vias = support.flatMap { it.via.split("、") }.filter { it.isNotBlank() }.distinct()
        val verdict = if (iss.verdict != "无法核实" && support.isEmpty() && iss.quote.isBlank() && iss.verdict == "已印证") "无法核实" else iss.verdict
        iss.copy(links = support.distinctBy { it.url }.take(3).map { FactLink(it.title, it.url, it.via) }, corroboration = vias.size, verdict = verdict,
            source = iss.source.ifBlank { support.firstOrNull()?.title.orEmpty() })
    }

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
        val g = searchAll(ctx, title, body)
        val hits = g.hits; val note = g.note; val qs = g.queries
        val prompt = buildPrompt(s.factPrompt, title, body, hits)
        val check = resolveCheckModel(s, writerDisplay)
        val target = Ai.target(ctx, check.ref)
        val reply = try {
            Ai.chatText(target, "你是事实校对编辑。只输出一个合法 JSON 对象。", prompt, true)
        } catch (e: ApiError) {
            throw refineFactModelError(e, check) ?: e
        }
        val (issues, skipped) = parse(reply)
        val ok = FactQuotes.annotate(attachEvidence(issues.filter { it.span.isBlank() || body.contains(it.span) || title.contains(it.span) }, hits), g.quotes)
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
