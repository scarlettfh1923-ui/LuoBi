package com.scarlett.luobi

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 1.17：一条说法拆出的原子细节（人物/时间/地点/事件/关系/引述归属/范围…） */
data class FactDetail(
    val id: String,
    val aspect: String,
    val text: String,
    /** 可核实事实 / 引述归属 / 修辞渲染 / 观点 */
    val type: String = FactPipeline.T_FACT,
    val entities: List<String> = emptyList(),
    val queries: List<String> = emptyList(),
    val verdict: String = "",
    val quote: String = "",
    val url: String = "",
    val sourceTitle: String = "",
    val via: String = "",
    val reason: String = "",
    val rewrite: String = "",
    val selected: Boolean = false,
    val applied: Boolean = false,
    /** 判「无法核实」时列出两轮用过的查询 */
    val tried: List<String> = emptyList(),
)

data class FactClaim(val id: String, val span: String, val claim: String, val details: List<FactDetail>, val done: Boolean = false)

/**
 * 1.17 事实校对流水线（按 factcheck-dryrun/report.md 验证过的做法）：
 * 1 抽说法并拆细节（+ 隐含检查：北平年代错置、「民国初年」口径）
 * 2 每个细节 2–3 条带实体的查询（绝不用文章主题）
 * 3 元宝优先 → Kimi → DeepSeek → 中文维基 → 英文维基 → 维基语录；取前几条全文（百科/传记/书优先）
 * 4 相关性过滤：只留提到该细节实体的段落
 * 5 逐条裁决，引文必须真在资料里；「无法核实」要先换说法再搜一轮
 */
object FactPipeline {
    const val T_FACT = "可核实事实"; const val T_ATTR = "引述归属"; const val T_RHET = "修辞渲染"; const val T_OPINION = "观点"
    const val OK = "已印证"; const val PART = "部分对"; const val BAD = "有出入"; const val UNK = "无法核实"; const val RHET = "渲染无出处"; const val OPN = "观点不校"
    val VERDICTS = listOf(BAD, PART, OK, UNK, RHET, OPN)
    const val CONCURRENCY = 4
    const val FULL_PER_DETAIL = 2
    const val PASSAGES_PER_DETAIL = 6

    /** 一次模型调用：judge=true 时是裁决（遵守 DeepSeek 深度思考开关） */
    fun interface Llm { suspend fun ask(system: String, user: String, judge: Boolean): String }
    class Env(val llm: Llm, val chain: List<FactQuotes.Src>, val page: (String) -> String = { "" }, val label: String = "")

    /** 测试 / 预览可替换 */
    @Volatile var envOverride: ((Context) -> Env)? = null

    data class Evidence(val id: String, val title: String, val url: String, val via: String, val text: String)

    // ---------------- 环境 ----------------
    fun env(ctx: Context, writerDisplay: String): Env {
        envOverride?.let { return it(ctx) }
        val s = Settings(ctx)
        val check = FactCheck.resolveCheckModel(s, writerDisplay)
        val target = Ai.target(ctx, check.ref)
        val ds = Ai.dsGen(ctx)
        val sel = FactCheck.SearchProvider.many(s.factSearch)
        fun keyOf(p: FactCheck.SearchProvider) = when (p) {
            FactCheck.SearchProvider.DEEPSEEK -> s.key("deepseek"); FactCheck.SearchProvider.KIMI -> s.key("kimi")
            FactCheck.SearchProvider.BOCHA -> s.bochaKey; FactCheck.SearchProvider.YUANBAO -> s.wsaKey; else -> ""
        }
        val ybKey = s.wsaKey
        val budget = java.util.concurrent.atomic.AtomicInteger(Yuanbao.BUDGET_PER_ARTICLE * 3)
        Yuanbao.resetSession()
        // 元宝有 Key 就排第一（她验证过的顺序）
        val order = (if (ybKey.isNotBlank()) listOf(FactCheck.SearchProvider.YUANBAO) else emptyList()) + sel
        val chain = FactQuotes.chain(order, ::keyOf, ctx) { budget.getAndDecrement() > 0 }
        val kimi = s.key("kimi")
        val llm = Llm { sys, user, judge ->
            try { Ai.chatText(target, sys, user, true, if (judge && ds.think) null else 4096, if (judge) ds else null) }
            catch (e: ApiError) { throw FactCheck.refineFactModelError(e, check) ?: e }
        }
        return Env(llm, chain, { u -> if (kimi.isNotBlank()) runCatching { FactSources.kimiFetch(kimi, u) }.getOrDefault("") else plainPage(u) },
            "校对模型 ${check.label} · ${check.sub}" + if (ds.think && check.ref == "p:deepseek") "（深度思考）" else "")
    }

    /** 免 Key 取网页正文（粗暴去标签） */
    fun plainPage(url: String): String = runCatching {
        val r = FactNet.call("GET", url, mapOf("User-Agent" to "Mozilla/5.0 LuoBi/1.17"), null)
        if (r.code !in 200..299) "" else r.body.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").take(60_000)
    }.getOrDefault("")

    // ---------------- 1 抽取 + 写查询 ----------------
    const val EXTRACT_SYS = "你是事实校对编辑。只输出一个合法 JSON 对象。"
    fun extractPrompt(title: String, body: String) = """把文章里的说法逐条拿出来，每条拆成原子细节，并为每个细节写 2–3 条检索查询。
细节维度：人物 / 时间 / 地点 / 地名 / 事件 / 关系 / 引述归属 / 范围 / 因果 / 状态。
类型只能是：可核实事实、引述归属（某人/某书说过、主张过）、修辞渲染（夸张、概括、氛围）、观点（作者自己的判断）。
查询规则：每条查询必须含该细节的具体人名/书名/地名/年份；不要用文章标题或文章主题（如「民国 开放 保守」）当查询；外国人名、书名另写一条英文原名查询。
span 必须是原文里一字不差的片段。
输出：{"claims":[{"span":"原文片段","claim":"这句说了什么","details":[{"aspect":"地点","text":"发生在北平","type":"可核实事实","entities":["丁玲","北平"],"queries":["丁玲 胡也频 1925 北京 同居","冯雪峰 丁玲 1928 杭州 葛岭"]}]}]}

标题：$title
正文：
${body.take(12000)}"""

    fun parseClaims(reply: String, body: String, title: String): List<FactClaim> {
        val j = obj(reply) ?: throw CleanParse.BadFormat(reply.take(200))
        val arr = j.optJSONArray("claims") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val span = o.optString("span").trim().let { if (body.contains(it) || title.contains(it)) it else "" }
            val ds = o.optJSONArray("details") ?: JSONArray()
            val cid = "c${i + 1}"
            val details = (0 until ds.length()).mapNotNull { k ->
                val d = ds.optJSONObject(k) ?: return@mapNotNull null
                FactDetail("$cid.d${k + 1}", d.optString("aspect").ifBlank { "事实" }, d.optString("text"), normType(d.optString("type")),
                    strs(d.optJSONArray("entities")), strs(d.optJSONArray("queries")))
            }.filter { it.text.isNotBlank() }
            if (details.isEmpty()) null else FactClaim(cid, span, o.optString("claim").ifBlank { span }, details)
        }
    }
    fun obj(reply: String): JSONObject? = runCatching { JSONObject(reply.trim()) }.getOrNull()
        ?: runCatching { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) }.getOrNull()
    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }
    fun normType(t: String) = when {
        t.contains("引述") || t.contains("归属") -> T_ATTR
        t.contains("渲染") || t.contains("修辞") -> T_RHET
        t.contains("观点") -> T_OPINION
        else -> T_FACT
    }

    // ---------------- 隐含检查 ----------------
    private val yearRe = Regex("(1[89]\\d{2})\\s*年")
    /** 文中出现的年份（含「1920年代」） */
    fun years(text: String): List<Int> = yearRe.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    fun implicit(title: String, body: String, claims: List<FactClaim>): List<FactClaim> {
        var out = claims
        val sentences = body.split(Regex("(?<=[。！？!?\\n])"))
        // 北平：1928年6月28日以前叫北京
        val bp = sentences.firstOrNull { it.contains("北平") }
        if (bp != null) {
            val ys = years(bp).ifEmpty { years(body) }
            val early = ys.any { it < 1928 } || Regex("19[01]\\d年|1920年代").containsMatchIn(bp)
            val have = out.any { c -> c.details.any { it.aspect.contains("地名") || (it.text.contains("北平") && it.text.contains("叫")) } }
            if (early && !have) {
                val d = FactDetail("", "地名", "当时这座城市叫不叫「北平」（1928年6月才由北京改名北平）", T_FACT, listOf("北平", "北京"),
                    listOf("1928年6月 北京 改名 北平", "北京 改称 北平 1928年6月28日", "北平 名称 由来 1928"))
                out = attach(out, bp.trim(), d, "当时这座城市的名字")
            }
        }
        // 「民国初年」却拿 1920 年代当例子
        if (body.contains("民国初年")) {
            val ys = years(body); val twenties = ys.any { it in 1920..1929 } || body.contains("1920年代")
            val have = out.any { c -> c.details.any { it.aspect.contains("口径") } }
            if (twenties && !have) {
                val s = sentences.first { it.contains("民国初年") }.trim()
                val people = out.flatMap { c -> c.details.flatMap { it.entities } }.filter { it.length in 2..4 && !it.any(Char::isDigit) && it !in GENERIC }.distinct().take(2)
                val ents = listOf("民国") + people
                val d = FactDetail("", "时间口径", "「民国初年」与文中用作例子的 1920 年代不一致（民国 1912 年建立）", T_FACT, ents,
                    listOf("中华民国 1912年 建立") + people.map { "$it 1925年 1928年" })
                out = attach(out, Regex("民国初年[^，。]{0,12}").find(s)?.value ?: "民国初年", d, "「民国初年」的时间口径")
            }
        }
        return out
    }
    private fun attach(claims: List<FactClaim>, span: String, d: FactDetail, claim: String): List<FactClaim> {
        val i = claims.indexOfFirst { it.span.isNotBlank() && (span.contains(it.span) || it.span.contains(span)) }
        return if (i >= 0) claims.mapIndexed { k, c -> if (k == i) c.copy(details = c.details + d.copy(id = "${c.id}.d${c.details.size + 1}")) else c }
        else { val id = "c${claims.size + 1}"; claims + FactClaim(id, span, claim, listOf(d.copy(id = "$id.d1"))) }
    }

    // ---------------- 2 查询清洗 ----------------
    val GENERIC = setOf("民国", "社会", "文人", "关系", "开放", "保守", "女性", "知识分子", "规范", "今天", "当时", "中国", "历史", "文化", "爱情", "婚恋", "恋爱", "自由")
    fun entitiesOf(d: FactDetail): List<String> = d.entities.map { it.trim() }.filter { it.length >= 2 && it !in GENERIC }.distinct()

    /** 每条查询必须带实体；不能是标题/主题；不足 2 条就用实体补齐；最多 3 条 */
    fun sanitize(d: FactDetail, title: String, extra: List<String> = emptyList()): List<String> {
        val ents = entitiesOf(d)
        val t = title.replace(Regex("[\\s\\p{P}\"“”]"), "")
        fun topical(q: String): Boolean { val c = q.replace(Regex("[\\s\\p{P}\"“”]"), ""); return c.isEmpty() || t.contains(c) || c.contains(t) && t.length >= 4 }
        val keep = (d.queries + extra).map { it.trim() }.filter { q -> q.isNotBlank() && !topical(q) && ents.any { e -> q.contains(e, ignoreCase = true) } }.distinct()
        val fill = mutableListOf<String>()
        if (ents.isNotEmpty()) {
            val ys = years(d.text).map { "$it" }
            fill += (ents.take(3) + ys.take(1)).joinToString(" ")
            fill += ents.first() + " " + d.aspect.takeIf { it !in listOf("事实") }.orEmpty()
        }
        return (keep + fill.map { it.trim() }).distinct().filter { it.isNotBlank() && !topical(it) }.take(3)
    }

    /** 第二轮：换说法（实体两两组合 + 英文 / 年份） */
    fun reformulate(d: FactDetail): List<String> {
        val ents = entitiesOf(d)
        val ys = years(d.text).map { "$it" }
        val out = mutableListOf<String>()
        if (ents.size >= 2) out += "${ents[0]} ${ents[1]}"
        ents.firstOrNull()?.let { e -> out += (listOf(e) + ys).joinToString(" "); out += "$e ${d.aspect} 史料" }
        ents.filter { it.any { c -> c in 'A'..'z' } }.forEach { out += it }
        return out.distinct().filter { it !in d.queries }.take(3)
    }

    // ---------------- 3/4 检索 + 相关性 ----------------
    /** 把全文切成段，只留提到实体的段（每段 ≤ 360 字） */
    fun relevant(text: String, ents: List<String>, cap: Int = 360): List<String> {
        if (ents.isEmpty()) return emptyList()
        val paras = text.split(Regex("\\n+")).flatMap { p -> if (p.length <= cap) listOf(p) else p.split(Regex("(?<=[。！？.!?])")).chunkedBy(cap) }
        return paras.map { it.trim() }.filter { p -> p.length >= 8 && ents.any { p.contains(it, ignoreCase = true) } }
            .sortedByDescending { p -> ents.count { p.contains(it, ignoreCase = true) } }
    }
    private fun List<String>.chunkedBy(cap: Int): List<String> { val out = mutableListOf<String>(); val b = StringBuilder()
        for (s in this) { if (b.length + s.length > cap && b.isNotEmpty()) { out += b.toString(); b.clear() }; b.append(s) }
        if (b.isNotEmpty()) out += b.toString(); return out }

    /** 百科 / 传记 / 书 优先 */
    fun rank(url: String): Int = FactEvidence.authority(url) + when {
        Regex("wikipedia|baike|wikiquote|britannica").containsMatchIn(url) -> 3
        Regex("library|chapter|book|biograph|wiley|sagepub|jstor|douban\\.com/subject|gov\\.cn|edu").containsMatchIn(url) -> 2
        else -> 0
    }

    private val netLock = Mutex()

    /** 沿兜底链搜一个细节：有相关段落就停 */
    suspend fun search(d: FactDetail, queries: List<String>, env: Env, used: MutableSet<String>): List<Evidence> {
        val ents = entitiesOf(d)
        val out = mutableListOf<Evidence>()
        for (src in env.chain) {
            val qs = if (src.english) queries.filter { q -> q.any { it in 'A'..'z' } }.ifEmpty { queries.take(1) } else queries
            for (q in qs) {
                val hits = netLock.withLock { runCatching { src.search(q) }.getOrDefault(emptyList()) }
                if (hits.isEmpty()) continue
                hits.sortedByDescending { rank(it.url) }.forEachIndexed { i, h ->
                    if (out.any { it.url == h.url }) return@forEachIndexed
                    var text = h.text.ifBlank { h.snippet }
                    if (i < FULL_PER_DETAIL && h.text.length < 400) {
                        val full = netLock.withLock { runCatching { src.full?.invoke(h) ?: (if (h.url.startsWith("http")) env.page(h.url) else "") }.getOrDefault("") }
                        if (full.isNotBlank()) text = full + "\n" + text
                    }
                    val pas = relevant(text, ents).take(2)
                    if (pas.isNotEmpty()) out += Evidence("", h.title, h.url, src.label, pas.joinToString(" … "))
                }
            }
            if (out.isNotEmpty()) { used += src.label; break }
        }
        return out.sortedByDescending { rank(it.url) }.take(PASSAGES_PER_DETAIL)
    }

    // ---------------- 5 裁决 ----------------
    const val JUDGE_SYS = "你是严谨的事实校对编辑。只依据给出的资料裁决，绝不编造引文或链接。只输出一个合法 JSON 对象。"
    fun judgePrompt(c: FactClaim, ev: Map<String, List<Evidence>>): String {
        val sb = StringBuilder("原文：「${c.span.ifBlank { c.claim }}」\n逐个细节裁决。\n")
        c.details.forEach { d ->
            sb.append("\n[${d.id}] ${d.aspect}｜${d.type}｜${d.text}\n")
            val es = ev[d.id].orEmpty()
            if (es.isEmpty()) sb.append("  （没有检索到提到相关实体的资料）\n")
            es.forEach { e -> sb.append("  [${e.id}] ${e.title}｜${e.url}\n  ${e.text}\n") }
        }
        sb.append("""
裁决只能是：已印证 / 部分对 / 有出入 / 无法核实 / 渲染无出处 / 观点不校。
- 引述归属按「意思」判：那本书/那个人是否真这样主张，不看关键词重合；意思被拔高、简化或换了说法就是「部分对」。
- 已印证 / 部分对 / 有出入 必须给出资料编号 evidence 和资料里一字不差的原句 quote；部分对、有出入要给 rewrite（可直接替换原文片段的改写）。
- 修辞渲染找不到资料支撑 → 渲染无出处；观点 → 观点不校；资料不够 → 无法核实。
输出：{"details":[{"id":"${c.details.first().id}","verdict":"部分对","evidence":"E1","quote":"…","reason":"一句话说明","rewrite":"…"}]}""")
        return sb.toString()
    }

    private fun norm(s: String) = s.replace(Regex("[\\s\\p{P}…「」『』“”\"'‘’（）()]"), "").lowercase()
    /** 引文是否真在资料里（容忍标点空白；长引文允许取一段） */
    fun quoteIn(quote: String, text: String): Boolean {
        val q = norm(quote); val t = norm(text)
        if (q.length < 4) return false
        if (t.contains(q)) return true
        val parts = quote.split(Regex("……|…|\\.\\.\\.")).map(::norm).filter { it.length >= 6 }
        return parts.isNotEmpty() && parts.all { t.contains(it) }
    }
    fun normVerdict(v: String) = VERDICTS.firstOrNull { v.contains(it) } ?: when {
        v.contains("渲染") -> RHET; v.contains("观点") -> OPN; v.contains("出入") || v.contains("错") -> BAD; v.contains("部分") -> PART; v.contains("印证") || v.contains("属实") -> OK; else -> UNK }

    fun applyJudge(c: FactClaim, reply: String, ev: Map<String, List<Evidence>>, only: Set<String>? = null): FactClaim {
        val j = obj(reply)
        val arr = j?.optJSONArray("details") ?: JSONArray()
        val by = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optString("id") }
        return c.copy(details = c.details.map { d ->
            if (only != null && d.id !in only) return@map d
            val o = by[d.id]
            if (d.type == T_OPINION) return@map d.copy(verdict = OPN, reason = o?.optString("reason").orEmpty().ifBlank { "作者自己的判断，不做事实校对" })
            if (o == null) return@map d.copy(verdict = UNK)
            var v = normVerdict(o.optString("verdict"))
            val es = ev[d.id].orEmpty()
            val quote = o.optString("quote").trim()
            val cited = o.optString("evidence").let { id -> es.firstOrNull { it.id == id.trim().trimStart('[').trimEnd(']') } }
            val hit = (listOfNotNull(cited) + es).firstOrNull { quoteIn(quote, it.text) }
            var reason = o.optString("reason")
            if (v in listOf(OK, PART, BAD) && hit == null) { v = UNK; reason = "裁决给的引文在资料里找不到，不采信" + if (reason.isNotBlank()) "（原判断：$reason）" else "" }
            if (v == RHET && d.type != T_RHET && hit == null && es.isEmpty()) v = UNK
            val rw = if (v in listOf(PART, BAD)) o.optString("rewrite").trim() else ""
            d.copy(verdict = v, quote = if (hit != null) quote else "", url = hit?.url.orEmpty(), sourceTitle = hit?.title.orEmpty(), via = hit?.via.orEmpty(),
                reason = reason, rewrite = rw, selected = rw.isNotBlank() && c.span.isNotBlank())
        })
    }

    private fun number(ds: List<FactDetail>, ev: Map<String, List<Evidence>>): Map<String, List<Evidence>> {
        var n = 0
        return ds.associate { d -> d.id to ev[d.id].orEmpty().map { it.copy(id = "E${++n}") } }
    }

    /** 处理一条说法：搜 → 判 → 无法核实的换说法再搜一轮 → 再判 */
    suspend fun checkClaim(c: FactClaim, title: String, env: Env, used: MutableSet<String>): FactClaim {
        val qd = c.details.map { d -> if (d.type == T_OPINION) d else d.copy(queries = sanitize(d, title)) }
        val raw = qd.associate { d -> d.id to (if (d.type == T_OPINION) emptyList() else search(d, d.queries, env, used)) }
        val ev = number(qd, raw)
        var cur = c.copy(details = qd)
        cur = applyJudge(cur, env.llm.ask(JUDGE_SYS, judgePrompt(cur, ev), true), ev)
        val retry = cur.details.filter { it.verdict == UNK && it.type != T_OPINION }
        if (retry.isNotEmpty()) {
            val q2 = retry.associate { it.id to reformulate(it) }
            val raw2 = retry.associate { d -> d.id to (raw[d.id].orEmpty() + search(d, q2[d.id].orEmpty(), env, used)).distinctBy { it.url } }
            val ev2 = number(cur.details, raw + raw2)
            if (raw2.any { (id, l) -> l.size > raw[id].orEmpty().size }) {
                cur = applyJudge(cur, env.llm.ask(JUDGE_SYS, judgePrompt(cur.copy(details = cur.details.filter { it.id in q2 }), ev2), true), ev2, q2.keys)
            }
            cur = cur.copy(details = cur.details.map { d -> if (d.id in q2 && d.verdict == UNK) d.copy(tried = d.queries + q2[d.id].orEmpty(),
                reason = d.reason.ifBlank { "两轮检索都没找到提到相关人物/事物的资料" }) else d })
        }
        return cur.copy(done = true)
    }

    // ---------------- 编排 ----------------
    class State(val claims: List<FactClaim>, val sources: List<String>)

    suspend fun run(title: String, body: String, env: Env, resume: State? = null,
                    save: suspend (State) -> Unit = {}, progress: suspend (Int, Int) -> Unit = { _, _ -> }): FactReport = coroutineScope {
        val claims0 = resume?.claims?.takeIf { it.isNotEmpty() }
            ?: implicit(title, body, parseClaims(env.llm.ask(EXTRACT_SYS, extractPrompt(title, body), false), body, title))
        val used: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { resume?.sources?.let { addAll(it) } }
        val cur = claims0.toMutableList()
        val lock = Mutex()
        save(State(cur.toList(), used.toList()))
        progress(cur.count { it.done }, cur.size)
        val sem = Semaphore(CONCURRENCY)
        cur.indices.filter { !cur[it].done }.map { i ->
            async { sem.withPermit {
                val r = checkClaim(cur[i], title, env, used)
                lock.withLock { cur[i] = r; save(State(cur.toList(), used.toList())); progress(cur.count { it.done }, cur.size) }
            } }
        }.awaitAll()
        report(cur, used.toList(), env.label)
    }

    fun report(claims: List<FactClaim>, sources: List<String>, label: String): FactReport {
        val issues = claims.flatMap { c -> c.details.map { d -> issueOf(c, d) } }
        val order = listOf("元宝搜索", "Kimi 搜索", "DeepSeek 搜索", "中文维基", "博查", "英文维基", "维基语录")
        val src = sources.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        val note = "检索：" + (src.ifEmpty { listOf("无") }).joinToString("、") + (if (label.isNotBlank()) " · $label" else "")
        return FactReport(issues, emptyList(), note, claims.flatMap { c -> c.details.flatMap { it.queries } }.distinct(), claims = claims, sources = src)
    }

    fun issueOf(c: FactClaim, d: FactDetail) = FactIssue(id = d.id, span = c.span, category = d.aspect, severity = if (d.verdict == BAD) "高" else "中",
        claim = d.text, correction = d.rewrite, source = d.sourceTitle, note = d.reason, selected = d.selected, aiCorrection = d.rewrite, applied = d.applied,
        verdict = d.verdict, quote = d.quote, links = if (d.url.isBlank()) emptyList() else listOf(FactLink(d.sourceTitle, d.url, d.via)), corroboration = if (d.url.isBlank()) 0 else 1)

    /** 整篇统计 */
    fun count(r: FactReport, v: String) = r.claims.sumOf { c -> c.details.count { it.verdict == v } }.takeIf { r.claims.isNotEmpty() } ?: r.issues.count { it.verdict == v }

    // ---------------- 序列化 ----------------
    fun encodeClaims(cs: List<FactClaim>): JSONArray = JSONArray().apply { cs.forEach { c ->
        put(JSONObject().put("id", c.id).put("span", c.span).put("claim", c.claim).put("done", c.done).put("details", JSONArray().apply { c.details.forEach { d ->
            put(JSONObject().put("id", d.id).put("aspect", d.aspect).put("text", d.text).put("type", d.type).put("entities", JSONArray(d.entities)).put("queries", JSONArray(d.queries))
                .put("verdict", d.verdict).put("quote", d.quote).put("url", d.url).put("sourceTitle", d.sourceTitle).put("via", d.via).put("reason", d.reason)
                .put("rewrite", d.rewrite).put("selected", d.selected).put("applied", d.applied).put("tried", JSONArray(d.tried)))
        } }))
    } }
    fun decodeClaims(a: JSONArray?): List<FactClaim> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        val ds = o.optJSONArray("details") ?: JSONArray()
        FactClaim(o.optString("id"), o.optString("span"), o.optString("claim"), (0 until ds.length()).mapNotNull { ds.optJSONObject(it) }.map { d ->
            FactDetail(d.optString("id"), d.optString("aspect"), d.optString("text"), d.optString("type"), strs(d.optJSONArray("entities")), strs(d.optJSONArray("queries")),
                d.optString("verdict"), d.optString("quote"), d.optString("url"), d.optString("sourceTitle"), d.optString("via"), d.optString("reason"),
                d.optString("rewrite"), d.optBoolean("selected"), d.optBoolean("applied"), strs(d.optJSONArray("tried")))
        }, o.optBoolean("done"))
    }
    fun encodeState(s: State): String = JSONObject().put("claims", encodeClaims(s.claims)).put("sources", JSONArray(s.sources)).toString()
    fun decodeState(json: String): State? = if (json.isBlank()) null else runCatching { val j = JSONObject(json); State(decodeClaims(j.optJSONArray("claims")), strs(j.optJSONArray("sources"))) }.getOrNull()

    /** 旧报告（1.12–1.16）没有 claims：每条 issue 当一个单细节说法 */
    fun claimsOf(r: FactReport): List<FactClaim> = r.claims.ifEmpty {
        r.issues.map { i -> FactClaim(i.id, i.span, i.claim, listOf(FactDetail(i.id, i.category, i.claim, T_FACT, verdict = i.verdict, quote = i.quote,
            url = i.links.firstOrNull()?.url.orEmpty(), sourceTitle = i.links.firstOrNull()?.title ?: i.source, via = i.links.firstOrNull()?.via.orEmpty(),
            reason = i.note, rewrite = i.correction, selected = i.selected, applied = i.applied)), true) }
    }
    /** 改了细节（勾选/手改改写）后同步回报告 */
    fun withClaims(r: FactReport, cs: List<FactClaim>): FactReport = r.copy(claims = cs, issues = cs.flatMap { c -> c.details.map { issueOf(c, it) } })
}
