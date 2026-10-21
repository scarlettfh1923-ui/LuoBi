package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 1.15.1 事实检索网络层。
 * 为什么之前 429：DeepSeek 搜索对 6 条查询用 async 同时发出；维基每条查询又连发 3–4 个请求，
 * 而且 DeepSeek 失败后兜底到维基时再整批来一遍——瞬时并发直接触发限流。
 * 现在：所有请求串行 + 固定间隔；429/503 按 Retry-After（或 X-RateLimit-Reset）退避重试，最多 3 次。
 */
object FactNet {
    data class Resp(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())
    /** 测试可替换 */
    var transport: (method: String, url: String, headers: Map<String, String>, body: String?) -> Resp = ::real
    var sleep: (Long) -> Unit = { Thread.sleep(it) }
    var gapMs = 400L
    var now: () -> Long = { System.currentTimeMillis() }
    const val TRIES = 3

    class Fail(val code: Int, msg: String) : java.io.IOException(msg)

    fun call(method: String, url: String, headers: Map<String, String>, body: String?): Resp {
        var last: Resp? = null
        for (attempt in 0 until TRIES) {
            sleep(gapMs)
            val r = transport(method, url, headers, body)
            if (r.code != 429 && r.code != 503) return r
            last = r
            if (attempt < TRIES - 1) sleep(backoff(r, attempt))
        }
        return last!!
    }

    /** Retry-After 秒数 / X-RateLimit-Reset（Unix 秒）优先；否则 1s、2s、4s；上限 8s */
    fun backoff(r: Resp, attempt: Int): Long {
        val h = r.headers.mapKeys { it.key.lowercase() }
        h["retry-after"]?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(500, 8000) }
        h["x-ratelimit-reset"]?.trim()?.toLongOrNull()?.let { return (it * 1000 - now()).coerceIn(500, 8000) }
        return (1000L shl attempt).coerceAtMost(8000)
    }

    private fun real(method: String, url: String, headers: Map<String, String>, body: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 30000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        return try {
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            Resp(code, txt, c.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() })
        } finally { c.disconnect() }
    }

    /** 把失败说成人话 */
    fun plain(label: String, e: Throwable?): String = when {
        e is Fail && e.code == 429 -> "${label}请求太频繁，被暂时限流"
        e is Fail && (e.code == 401 || e.code == 403) -> "${label} Key 无效或没有权限"
        e is Fail && e.code == 402 -> "${label}余额不足"
        e is Fail -> "${label}返回错误（HTTP ${e.code}）"
        e is MissingKey -> "${label}未填写 Key"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "${label}连不上（网络问题）"
        else -> "${label}出错：${e?.message?.take(40) ?: "未知原因"}"
    }
    class MissingKey : Exception()
}

/** 各检索源（全部走 FactNet：串行 + 退避） */
object FactSources {
    private const val UA = "LuoBi/1.15.1 (Android; fact-check; contact via GitHub scarlettfh1923-ui/LuoBi)"
    private fun ok(r: FactNet.Resp): String { if (r.code !in 200..299) throw FactNet.Fail(r.code, "HTTP ${r.code}"); return r.body }
    private fun json(k: String) = mapOf("Authorization" to "Bearer $k", "Content-Type" to "application/json")

    fun deepseek(key: String, q: String, via: String = "DeepSeek"): List<FactHit> {
        val b = JSONObject().put("query", "$q lang:zh,en".take(128)).put("count", 5).put("need_content", false)
        val arr = JSONObject(ok(FactNet.call("POST", "https://api.deepseek.com/v1/tools/search", json(key), b.toString()))).optJSONArray("results") ?: return emptyList()
        return objs(arr).map { FactHit(it.optString("title"), it.optString("url"), it.optString("summary").ifBlank { it.optString("snippet") }.take(500), it.optString("site_name").ifBlank { "网页" }, via) }
    }

    /** Kimi 联网搜索 Basic：POST /v1/tools/search {"text_query","limit","timeout_seconds"[,"include_content"]} */
    fun kimiSearch(key: String, q: String, includeContent: Boolean = false): List<FactHit> {
        val b = JSONObject().put("text_query", q).put("limit", 5).put("timeout_seconds", 10)
        if (includeContent) b.put("include_content", true)
        val arr = JSONObject(ok(FactNet.call("POST", "https://api.moonshot.cn/v1/tools/search", json(key), b.toString()))).optJSONArray("search_results") ?: return emptyList()
        return objs(arr).map { FactHit(it.optString("title"), it.optString("url"), it.optString("snippet").take(500), it.optString("site_name").ifBlank { "网页" }, "Kimi",
            text = it.optString("text"), date = it.optString("date")) }
    }

    /** Kimi 网页抓取：POST /v1/tools/fetch {"url"} → markdown（¥0.01/次） */
    fun kimiFetch(key: String, url: String): String =
        JSONObject(ok(FactNet.call("POST", "https://api.moonshot.cn/v1/tools/fetch", json(key), JSONObject().put("url", url).toString()))).optString("markdown")

    fun bocha(key: String, q: String): List<FactHit> {
        val b = JSONObject().put("query", q).put("count", 5).put("summary", true).put("freshness", "noLimit")
        val j = JSONObject(ok(FactNet.call("POST", "https://api.bochaai.com/v1/web-search", json(key), b.toString())))
        val pages = (j.optJSONObject("data") ?: j).optJSONObject("webPages")?.optJSONArray("value") ?: JSONArray()
        return objs(pages).map { FactHit(it.optString("name"), it.optString("url"), it.optString("summary").ifBlank { it.optString("snippet") }.take(500), it.optString("siteName").ifBlank { "网页" }, "博查") }
    }

    private val wikiH = mapOf("User-Agent" to UA, "Accept" to "application/json")
    /** 维基：1 次搜索（最多 3 条） */
    fun wikiSearch(q: String): List<FactHit> {
        val u = "https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(q)}&srlimit=3&format=json&utf8=1"
        val arr = JSONObject(ok(FactNet.call("GET", u, wikiH, null))).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return objs(arr).map { o -> val t = o.optString("title")
            FactHit(t, "https://zh.wikipedia.org/wiki/" + enc(t.replace(' ', '_')), o.optString("snippet").replace(Regex("<[^>]+>"), ""), "维基百科", "维基") }
    }
    /** 维基全文纯文本（情节核对用） */
    fun wikiExtract(title: String): String {
        val u = "https://zh.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&variant=zh-cn&format=json&titles=${enc(title)}"
        val pages = JSONObject(ok(FactNet.call("GET", u, wikiH, null))).optJSONObject("query")?.optJSONObject("pages") ?: return ""
        return pages.keys().asSequence().mapNotNull { pages.optJSONObject(it)?.optString("extract") }.firstOrNull().orEmpty()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun objs(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

/** 1.15.1：查询规划、多源检索、情节全文证据 */
object FactEvidence {
    /** 每篇最多查询数、每项/每次最多抓取数、证据字数上限 */
    const val MAX_QUERIES = 4
    const val MAX_FETCH_PER_ITEM = 3
    const val MAX_FETCH_PER_RUN = 15
    const val DOC_CAP = 1500
    const val TOTAL_CAP = 9000

    private val workRe = Regex("《([^》]{1,30})》")
    private val plotWords = listOf("结局", "最后", "最终", "死", "杀", "嫁", "娶", "爱上", "离开", "重逢", "背叛", "分手", "结婚", "自杀", "父亲", "母亲", "儿子", "女儿", "情人", "丈夫", "妻子", "复仇", "原来", "真相", "失踪", "回到")

    /** 情节类句子（含作品名的上下文里，出现人物命运/关系/结局的词） */
    fun plotSentences(body: String): List<String> =
        body.split(Regex("[。！？\\n]")).map { it.trim() }.filter { s -> s.length in 6..120 && plotWords.any { s.contains(it) } }

    data class PlotItem(val work: String, val sentence: String, val people: List<String>)
    private val personRe = Regex("([\\p{IsHan}]{2,4})(?=(?:最后|最终|和|与|跟|爱上|嫁|娶|死|杀|离开|重逢|背叛|回到|的父亲|的母亲|的丈夫|的妻子))")

    /** 情节项：每个作品 + 一句代表性的情节句，最多 3 项 */
    fun plotItems(title: String, body: String): List<PlotItem> {
        val works = workRe.findAll(title + "\n" + body).map { it.groupValues[1] }.distinct().toList()
        if (works.isEmpty()) return emptyList()
        val sents = plotSentences(body)
        return works.take(2).flatMap { w ->
            val mine = sents.filter { it.contains(w) }.ifEmpty { sents }
            mine.take(2).map { s -> PlotItem(w, s, personRe.findAll(s).map { it.groupValues[1] }.filter { it != w && !plotWords.contains(it) }.distinct().take(2).toList()) }
        }.distinctBy { it.sentence }.take(3)
    }

    /** 情节定向查询：「作品 人物 结局 剧情」 */
    fun plotQuery(p: PlotItem): String = listOf(p.work, p.people.joinToString(" "), if (p.sentence.contains("结局") || p.sentence.contains("最后") || p.sentence.contains("最终")) "结局" else "剧情", "剧情简介")
        .filter { it.isNotBlank() }.joinToString(" ")

    /** 去重并合并相近查询：规范化后互为子串的只留更长的一条 */
    fun dedupe(qs: List<String>, max: Int = MAX_QUERIES): List<String> {
        fun norm(s: String) = s.replace(Regex("[\\s\\p{P}《》「」“”]"), "").lowercase()
        val out = mutableListOf<String>()
        for (q in qs.map { it.trim() }.filter { it.length >= 2 }) {
            val n = norm(q)
            val i = out.indexOfFirst { norm(it).let { o -> o.contains(n) || n.contains(o) } }
            if (i < 0) out += q else if (n.length > norm(out[i]).length) out[i] = q
        }
        return out.take(max)
    }

    /** 权威页优先：维基 / 百度百科 / 豆瓣 */
    fun authority(url: String): Int = when {
        "wikipedia.org" in url -> 3
        "baike.baidu.com" in url -> 3
        "douban.com" in url -> 2
        "baike.sogou" in url || "zhihu.com" in url -> 1
        else -> 0
    }

    /** 从长文里只留和这条情节相关的段落（命中作品名/人物/情节词越多越靠前），不超过 cap 字 */
    fun trim(text: String, keys: List<String>, cap: Int = DOC_CAP): String {
        val paras = text.split(Regex("\\n+")).map { it.trim() }.filter { it.length >= 8 && !it.startsWith("![") }
        val k = keys.filter { it.length >= 2 }
        val scored = paras.mapIndexed { i, p -> Triple(i, p, k.count { p.contains(it) } * 3 + plotWords.count { p.contains(it) } + if (p.contains("剧情") || p.contains("情节") || p.contains("结局")) 4 else 0) }
            .filter { it.third > 0 }.sortedByDescending { it.third }
        val keep = mutableListOf<Triple<Int, String, Int>>(); var n = 0
        for (s in scored) { if (n >= cap) break; keep += s; n += s.second.length }
        return keep.sortedBy { it.first }.joinToString("\n") { it.second }.take(cap)
    }
}
