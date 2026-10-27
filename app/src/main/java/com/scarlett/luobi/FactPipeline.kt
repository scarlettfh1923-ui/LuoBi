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
    /** 2.1：无法核实时更稳妥的说法（模型给的；空则本地兜底） */
    val soft: String = "",
)

data class FactClaim(val id: String, val span: String, val claim: String, val details: List<FactDetail>, val done: Boolean = false,
    /** 2.1：逐条处理结果：""=待处理 / applied=已采纳 / ignored=已忽略 / manual=手动改 */
    val fix: String = "",
    /** 2.1：「换个改法」得到的备选；fixText = 当前选中的改法（空 = 用默认建议） */
    val alts: List<String> = emptyList(), val fixText: String = "")
/** 2.1：分拣结果（硬事实 / 观点 / 描写句数） */
data class FactTriage(val facts: Int, val opinions: Int, val descriptions: Int)

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
    class Env(val llm: Llm, val chain: List<FactQuotes.Src>, val page: (String) -> String = { "" }, val label: String = "", val status: SourceStatus = SourceStatus())

    /** 1.19：记录每个检索源这次的表现；Key 错 / 余额不足 / 连不上的源本次不再调用 */
    class SourceStatus {
        private val ok = ConcurrentHashMap<String, Int>()
        private val err = ConcurrentHashMap<String, String>()
        private val dead: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val notes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        fun dead(label: String) = label in dead
        fun success(label: String) { ok.merge(label, 1, Int::plus) }
        fun skip(label: String, why: String) { dead += label; err[label] = why }
        fun fail(label: String, e: Throwable) {
            val k = classify(e)
            err[label] = k
            if (k == "Key 错" || k == "余额不足") dead += label
        }
        fun summary(): String {
            val names = (ok.keys + err.keys).distinct()
            val parts = names.map { n -> val o = ok[n] ?: 0; val e = err[n]
                n + " " + when { o > 0 && e != null -> "成功（偶有$e）"; o > 0 -> "成功"; e != null -> e; else -> "无结果" } }
            return (parts + notes).joinToString(" · ")
        }
        companion object {
            fun classify(e: Throwable): String = when {
                e is FactNet.Fail && e.code == 429 -> "限流"
                e is FactNet.Fail && (e.code == 401 || e.code == 403) -> "Key 错"
                e is FactNet.Fail && e.code == 402 -> "余额不足"
                e is java.net.SocketTimeoutException -> "超时"
                e is java.net.UnknownHostException || e is java.net.ConnectException || e is javax.net.ssl.SSLException -> "连不上"
                e is FactNet.MissingKey -> "未填 Key"
                e.message?.contains("预算") == true -> "预算用完"
                else -> "出错"
            }
        }
    }

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
        val status = SourceStatus()
        val chain0 = FactQuotes.chain(order, ::keyOf, ctx) { (budget.getAndDecrement() > 0).also { if (!it) status.notes += "元宝本篇额度用完" } }
        val chain = wikiGate(chain0, status)
        val kimi = s.key("kimi")
        val llm = llmFor(target, check, ds)
        return Env(llm, chain, { u -> if (kimi.isNotBlank()) runCatching { FactSources.kimiFetch(kimi, u) }.getOrDefault("") else plainPage(u) },
            "校对模型 ${check.label} · ${check.sub}" + if (ds.think && check.ref == "p:deepseek") "（深度思考）" else "", status)
    }

    /** 2.1：只要模型、不要检索链（换个改法 / 风格把关用）；judge=true 时遵守深度思考开关与温度 */
    @Volatile var llmOverride: Llm? = null
    fun llm(ctx: Context, writerDisplay: String): Llm {
        llmOverride?.let { return it }
        val check = FactCheck.resolveCheckModel(Settings(ctx), writerDisplay)
        return llmFor(Ai.target(ctx, check.ref), check, Ai.dsGen(ctx))
    }
    private fun llmFor(target: Ai.Target, check: ModelRef, ds: Ai.DsGen) = Llm { sys, user, judge ->
        try { Ai.chatText(target, sys, user, true, if (judge && ds.think) null else if (judge) 4096 else 8192, if (judge) ds else null) }
        catch (e: ApiError) { throw FactCheck.refineFactModelError(e, check) ?: e }
    }

    const val WIKI_PROBE = "https://zh.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json"
    /** 1.19：3 秒探测维基；连不上（国内常见）就整组跳过，不再每条都等超时 */
    fun wikiGate(chain: List<FactQuotes.Src>, status: SourceStatus): List<FactQuotes.Src> {
        val wiki = chain.filter { isWiki(it.label) }
        if (wiki.isEmpty() || FactNet.probe(WIKI_PROBE, 3000)) return chain
        wiki.forEach { status.skip(it.label, "连不上（已跳过）") }
        return chain - wiki.toSet()
    }
    fun isWiki(label: String) = label.contains("维基")

    /** 免 Key 取网页正文（粗暴去标签） */
    fun plainPage(url: String): String = runCatching {
        val (b, ct) = FactNet.bytes(url)
        if (b.isEmpty()) "" else FactNet.decode(b, ct).replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").take(60_000)
    }.getOrDefault("")

    // ---------------- 1 抽取 + 写查询 ----------------
    const val EXTRACT_SYS = "你是事实校对编辑。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE
    fun extractPrompt(title: String, body: String) = """先把文章逐句分拣成三类：硬事实（数字、日期、人物、机构、引语、事件——可以用公开资料核对的）、观点（作者自己的判断）、描写（文学描写、氛围、比喻、个人经历感受）。
只把「硬事实」拿出来核查；观点和描写只计数，不要放进 claims。
每条 claim 对应原文里一句话（span 必须是原文里一字不差的片段，尽量是完整的一句），只含一个最值得核对的要点；同一句有多个要点可拆成多个 details。
aspect 只能是：数字 / 日期 / 人物 / 机构 / 引语 / 事件。
entities 里同一人物/事物的别名、简称、外文原名都列出来（如「胡适」「胡适之」「Hu Shih」）。
每个细节写 2–3 条检索查询：必须含具体人名/机构名/书名/地名/年份；不要用文章标题或主题当查询；外国人名、书名另写一条英文原名查询。
输出：{"triage":{"facts":6,"opinions":9,"descriptions":14},"claims":[{"span":"原文片段","claim":"这句说了什么","details":[{"aspect":"数字","text":"红果短剧月活 3 亿","type":"可核实事实","entities":["红果短剧"],"queries":["红果短剧 月活跃用户 2024","红果短剧 MAU QuestMobile"]}]}]}

标题：$title
正文：
$body"""

    /** 1.19：按段落切块（每块约 2500 字），长文不再被截断 */
    const val CHUNK = 2500
    fun chunks(body: String, size: Int = CHUNK): List<String> {
        if (body.length <= size) return listOf(body)
        val out = mutableListOf<String>(); val b = StringBuilder()
        for (p in body.split(Regex("(?<=\n)"))) {
            if (b.isNotEmpty() && b.length + p.length > size) { out += b.toString(); b.clear() }
            if (p.length > size) { p.chunked(size).forEach { out += it } } else b.append(p)
        }
        if (b.isNotBlank()) out += b.toString()
        return out.filter { it.isNotBlank() }
    }
    /** 逐块抽取（每块失败重试 1 次），合并并重新编号 */
    /** 2.1：最近一次抽取的分拣计数（extractAll 写入） */
    fun triageOf(reply: String): FactTriage? = obj(reply)?.optJSONObject("triage")?.let { FactTriage(it.optInt("facts"), it.optInt("opinions"), it.optInt("descriptions")) }
    suspend fun extractAll(title: String, body: String, env: Env, triage: (FactTriage) -> Unit = {}): List<FactClaim> {
        var tri = FactTriage(0, 0, 0)
        val parts = chunks(body)
        val all = mutableListOf<FactClaim>(); var failed = 0; var lastErr: Throwable? = null
        for ((i, part) in parts.withIndex()) {
            val tag = if (parts.size > 1) "（全文第 ${i + 1}/${parts.size} 段）" else ""
            var got: List<FactClaim>? = null
            for (attempt in 0..1) {
                try { val reply = env.llm.ask(EXTRACT_SYS, extractPrompt(title + tag, part), false); got = parseClaims(reply, body, title)
                    triageOf(reply)?.let { t -> tri = FactTriage(tri.facts + t.facts, tri.opinions + t.opinions, tri.descriptions + t.descriptions) }; break }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: ApiError) { if (e.code in listOf(401, 402, 403)) throw e; lastErr = e }
                catch (e: Throwable) { lastErr = e }
            }
            if (got == null) failed++ else all += got
        }
        if (all.isEmpty() && failed > 0) throw lastErr!!
        if (failed > 0) env.status.notes += "有 $failed 段没能拆出说法"
        triage(tri)
        return prioritize(all).mapIndexed { n, c -> val cid = "c${n + 1}"
            c.copy(id = cid, details = c.details.mapIndexed { k, d -> d.copy(id = "$cid.d${k + 1}") }) }
    }

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

    // ---------------- 2.1 分类与优先级 ----------------
    val KINDS = listOf("数字", "引语", "日期", "人物", "机构", "事件")
    private val digitRe = Regex("\\d")
    /** 列表上显示的类别：数字 / 引语 / 日期 / 人物 / 机构 / 事件 */
    fun kindOf(c: FactClaim): String {
        val a = c.details.map { it.aspect }
        KINDS.firstOrNull { k -> a.any { it.contains(k) } }?.let { return it }
        return when {
            a.any { it.contains("引述") } || c.span.contains("“") || c.span.contains("「") -> "引语"
            a.any { it.contains("时间") || it.contains("年代") } -> "日期"
            a.any { it.contains("地") } -> "事件"
            digitRe.containsMatchIn(c.span) -> "数字"
            else -> "事件"
        }
    }
    /** 数字、引语最容易错，先查 */
    fun priority(c: FactClaim): Int = KINDS.indexOf(kindOf(c)).let { if (it < 0) 9 else it }
    fun prioritize(cs: List<FactClaim>): List<FactClaim> = cs.withIndex().sortedWith(compareBy({ priority(it.value) }, { it.index })).map { it.value }
    /** 只含观点/渲染的说法不进核查列表 */
    fun checkable(c: FactClaim) = c.details.any { it.type != T_OPINION && it.type != T_RHET }

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
    /** 1.19：相关性过滤用的别名——外文译名按「·」拆开、去书名号 */
    fun aliases(ents: List<String>): List<String> = (ents + ents.flatMap { e -> e.split('·', '•', '・').map { it.trim() }.filter { it.length >= 2 && it !in GENERIC } } +
        ents.map { it.removePrefix("《").removeSuffix("》") }).filter { it.length >= 2 }.distinct()

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
    fun relevant(text: String, ents0: List<String>, cap: Int = 360): List<String> {
        if (ents0.isEmpty()) return emptyList()
        val ents = aliases(ents0)
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

    /** 1.19：每个源各自排队（同源不并发，免得限流），不同源同时查 */
    private val srcLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockOf(label: String) = srcLocks.getOrPut(label) { Mutex() }
    private suspend fun <T> guarded(src: FactQuotes.Src, env: Env, empty: T, f: () -> T): T {
        if (env.status.dead(src.label)) return empty
        return lockOf(src.label).withLock {
            try { f().also { env.status.success(src.label) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { env.status.fail(src.label, e); empty }
        }
    }

    /** 沿兜底链搜一个细节：有相关段落就停 */
    suspend fun search(d: FactDetail, queries: List<String>, env: Env, used: MutableSet<String>): List<Evidence> {
        val ents = entitiesOf(d)
        val tiers = env.chain.partition { !isWiki(it.label) }.toList().filter { it.isNotEmpty() }
        for (tier in tiers) {
            val res = coroutineScope { tier.map { src -> async(kotlinx.coroutines.Dispatchers.IO) { src to searchOne(src, d, ents, queries, env) } }.awaitAll() }
            val out = res.flatMap { (src, l) -> if (l.isNotEmpty()) used += src.label; l }.distinctBy { it.url }
            if (out.isNotEmpty()) return out.sortedByDescending { rank(it.url) }.take(PASSAGES_PER_DETAIL)
        }
        return emptyList()
    }

    private suspend fun searchOne(src: FactQuotes.Src, d: FactDetail, ents: List<String>, queries: List<String>, env: Env): List<Evidence> {
        val out = mutableListOf<Evidence>()
        run {
            val qs = if (src.english) queries.filter { q -> q.any { it in 'A'..'z' } }.ifEmpty { queries.take(1) } else queries
            for (q in qs) {
                val hits = guarded(src, env, emptyList()) { src.search(q) }
                if (hits.isEmpty()) continue
                hits.sortedByDescending { rank(it.url) }.forEachIndexed { i, h ->
                    if (out.any { it.url == h.url }) return@forEachIndexed
                    var text = h.text.ifBlank { h.snippet }
                    if (i < FULL_PER_DETAIL && h.text.length < 400) {
                        val full = if (src.full != null) guarded(src, env, "") { src.full.invoke(h) } else runCatching { if (h.url.startsWith("http")) env.page(h.url) else "" }.getOrDefault("")
                        if (full.isNotBlank()) text = full + "\n" + text
                    }
                    val pas = relevant(text, ents).take(2)
                    if (pas.isNotEmpty()) out += Evidence("", h.title, h.url, src.label, pas.joinToString(" … "))
                }
            }
        }
        return out
    }

    // ---------------- 5 裁决 ----------------
    const val JUDGE_SYS = "你是严谨的事实校对编辑。只依据给出的资料裁决，绝不编造引文或链接。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE
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
- 只能依据上面给出的资料，不许凭记忆下结论；资料里没有直接说到的一律「无法核实」。
- 已印证 / 部分对 / 有出入 必须给出资料编号 evidence 和资料里一字不差的原句 quote；部分对、有出入要给 rewrite：对原文片段做最小改动，只改错的那几个字词，句式、语气、标点保持原样，可直接替换原文片段。
- 无法核实时给 soft：更稳妥的说法（如加「据报道」、去掉具体数字改成约数），同样只做最小改动。
- 修辞渲染找不到资料支撑 → 渲染无出处；观点 → 观点不校；资料不够 → 无法核实。
输出：{"details":[{"id":"${c.details.first().id}","verdict":"部分对","evidence":"E1","quote":"…","reason":"一句话说明","rewrite":"…","soft":""}]}""")
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
    /** 1.19：模糊引文——双字覆盖率 ≥ 0.7 视为大意一致 */
    const val FUZZY = 0.7
    fun quoteNear(quote: String, text: String): Boolean {
        val q = norm(quote); val t = norm(text)
        if (q.length < 6) return false
        val grams = (0 until q.length - 1).map { q.substring(it, it + 2) }
        val tg = (0 until maxOf(0, t.length - 1)).mapTo(HashSet()) { t.substring(it, it + 2) }
        return grams.count { it in tg }.toDouble() / grams.size >= FUZZY
    }
    const val FUZZY_NOTE = "（引文与资料大意一致，未逐字对上）"
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
            var hit = (listOfNotNull(cited) + es).firstOrNull { quoteIn(quote, it.text) }
            var reason = o.optString("reason")
            if (hit == null && v in listOf(OK, PART, BAD)) (listOfNotNull(cited) + es).firstOrNull { quoteNear(quote, it.text) }?.let { hit = it; reason += FUZZY_NOTE }
            if (v in listOf(OK, PART, BAD) && hit != null && !hit!!.url.startsWith("http")) { hit = null }
            if (v in listOf(OK, PART, BAD) && hit == null) { v = UNK; reason = "裁决给的引文在资料里找不到，不采信" + if (reason.isNotBlank()) "（原判断：$reason）" else "" }
            if (v == RHET && d.type != T_RHET && hit == null && es.isEmpty()) v = UNK
            val rw = if (v in listOf(PART, BAD)) o.optString("rewrite").trim() else ""
            d.copy(verdict = v, quote = if (hit != null) quote else "", url = hit?.url.orEmpty(), sourceTitle = hit?.title.orEmpty(), via = hit?.via.orEmpty(),
                reason = reason, rewrite = rw, selected = rw.isNotBlank() && c.span.isNotBlank(),
                soft = if (v == UNK) o.optString("soft").trim().takeIf { it.isNotBlank() && it != c.span }.orEmpty() else "")
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
    class State(val claims: List<FactClaim>, val sources: List<String>, val triage: FactTriage? = null)

    suspend fun run(title: String, body: String, env: Env, resume: State? = null,
                    save: suspend (State) -> Unit = {}, progress: suspend (Int, Int) -> Unit = { _, _ -> }): FactReport = coroutineScope {
        var tri: FactTriage? = resume?.triage
        val claims0 = resume?.claims?.takeIf { it.isNotEmpty() }
            ?: prioritize(implicit(title, body, extractAll(title, body, env) { tri = it }).filter(::checkable)).mapIndexed { n, c -> val cid = "c${n + 1}"
                c.copy(id = cid, details = c.details.mapIndexed { k, d -> d.copy(id = "$cid.d${k + 1}") }) }
        val used: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { resume?.sources?.let { addAll(it) } }
        val cur = claims0.toMutableList()
        val lock = Mutex()
        save(State(cur.toList(), used.toList(), tri))
        progress(cur.count { it.done }, cur.size)
        val sem = Semaphore(CONCURRENCY)
        cur.indices.filter { !cur[it].done }.map { i ->
            async { sem.withPermit {
                val r = checkSafe(cur[i], title, env, used)
                lock.withLock { cur[i] = r; save(State(cur.toList(), used.toList(), tri)); progress(cur.count { it.done }, cur.size) }
            } }
        }.awaitAll()
        report(cur, used.toList(), env.label, env.status.summary()).copy(triage = tri ?: FactTriage(cur.size, 0, 0))
    }

    const val UNDONE = "未完成："
    /** 1.19：单条说法出错重试 2 次；还不行就标「无法核实（未完成）」，其他说法照常出报告 */
    suspend fun checkSafe(c: FactClaim, title: String, env: Env, used: MutableSet<String>): FactClaim {
        var last: Throwable? = null
        for (attempt in 0..2) {
            try { return checkClaim(c, title, env, used) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: ApiError) { if (e.code in listOf(401, 402, 403)) throw e; last = e }
            catch (e: Throwable) { last = e }
        }
        val why = UNDONE + (last?.message?.take(40) ?: "未知原因")
        return c.copy(done = true, details = c.details.map { it.copy(verdict = if (it.type == T_OPINION) OPN else UNK, reason = why) })
    }

    fun report(claims: List<FactClaim>, sources: List<String>, label: String, status: String = ""): FactReport {
        val issues = claims.flatMap { c -> c.details.map { d -> issueOf(c, d) } }
        val order = listOf("元宝搜索", "Kimi 搜索", "DeepSeek 搜索", "中文维基", "博查", "英文维基", "维基语录")
        val src = sources.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        val note = "检索：" + (src.ifEmpty { listOf("无") }).joinToString("、") + (if (label.isNotBlank()) " · $label" else "")
        return FactReport(issues, emptyList(), note, claims.flatMap { c -> c.details.flatMap { it.queries } }.distinct(), claims = claims, sources = src,
            status = status + claims.count { c -> c.details.any { it.reason.startsWith(UNDONE) } }.let { if (it > 0) (if (status.isBlank()) "" else " · ") + "$it 条没查完（报告不完整）" else "" })
    }

    fun issueOf(c: FactClaim, d: FactDetail) = FactIssue(id = d.id, span = c.span, category = d.aspect, severity = if (d.verdict == BAD) "高" else "中",
        claim = d.text, correction = d.rewrite, source = d.sourceTitle, note = d.reason, selected = d.selected, aiCorrection = d.rewrite, applied = d.applied,
        verdict = d.verdict, quote = d.quote, links = if (d.url.isBlank()) emptyList() else listOf(FactLink(d.sourceTitle, d.url, d.via)), corroboration = if (d.url.isBlank()) 0 else 1)

    /** 整篇统计 */
    fun count(r: FactReport, v: String) = r.claims.sumOf { c -> c.details.count { it.verdict == v } }.takeIf { r.claims.isNotEmpty() } ?: r.issues.count { it.verdict == v }

    // ---------------- 序列化 ----------------
    fun encodeClaims(cs: List<FactClaim>): JSONArray = JSONArray().apply { cs.forEach { c ->
        put(JSONObject().put("id", c.id).put("span", c.span).put("claim", c.claim).put("done", c.done).put("fix", c.fix).put("alts", JSONArray(c.alts)).put("fixText", c.fixText).put("details", JSONArray().apply { c.details.forEach { d ->
            put(JSONObject().put("id", d.id).put("aspect", d.aspect).put("text", d.text).put("type", d.type).put("entities", JSONArray(d.entities)).put("queries", JSONArray(d.queries))
                .put("verdict", d.verdict).put("quote", d.quote).put("url", d.url).put("sourceTitle", d.sourceTitle).put("via", d.via).put("reason", d.reason)
                .put("rewrite", d.rewrite).put("selected", d.selected).put("applied", d.applied).put("tried", JSONArray(d.tried)).put("soft", d.soft))
        } }))
    } }
    fun decodeClaims(a: JSONArray?): List<FactClaim> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        val ds = o.optJSONArray("details") ?: JSONArray()
        FactClaim(o.optString("id"), o.optString("span"), o.optString("claim"), (0 until ds.length()).mapNotNull { ds.optJSONObject(it) }.map { d ->
            FactDetail(d.optString("id"), d.optString("aspect"), d.optString("text"), d.optString("type"), strs(d.optJSONArray("entities")), strs(d.optJSONArray("queries")),
                d.optString("verdict"), d.optString("quote"), d.optString("url"), d.optString("sourceTitle"), d.optString("via"), d.optString("reason"),
                d.optString("rewrite"), d.optBoolean("selected"), d.optBoolean("applied"), strs(d.optJSONArray("tried")), d.optString("soft"))
        }, o.optBoolean("done"), o.optString("fix"), strs(o.optJSONArray("alts")), o.optString("fixText"))
    }
    fun encodeState(s: State): String = JSONObject().put("claims", encodeClaims(s.claims)).put("sources", JSONArray(s.sources))
        .apply { s.triage?.let { put("triage", JSONObject().put("facts", it.facts).put("opinions", it.opinions).put("descriptions", it.descriptions)) } }.toString()
    fun decodeState(json: String): State? = if (json.isBlank()) null else runCatching { val j = JSONObject(json)
        State(decodeClaims(j.optJSONArray("claims")), strs(j.optJSONArray("sources")), j.optJSONObject("triage")?.let { FactTriage(it.optInt("facts"), it.optInt("opinions"), it.optInt("descriptions")) }) }.getOrNull()

    /** 旧报告（1.12–1.16）没有 claims：每条 issue 当一个单细节说法 */
    fun claimsOf(r: FactReport): List<FactClaim> = r.claims.ifEmpty {
        r.issues.map { i -> FactClaim(i.id, i.span, i.claim, listOf(FactDetail(i.id, i.category, i.claim, T_FACT, verdict = i.verdict, quote = i.quote,
            url = i.links.firstOrNull()?.url.orEmpty(), sourceTitle = i.links.firstOrNull()?.title ?: i.source, via = i.links.firstOrNull()?.via.orEmpty(),
            reason = i.note, rewrite = i.correction, selected = i.selected, applied = i.applied)), true) }
    }
    /** 改了细节（勾选/手改改写）后同步回报告 */
    fun withClaims(r: FactReport, cs: List<FactClaim>): FactReport = r.copy(claims = cs, issues = cs.flatMap { c -> c.details.map { issueOf(c, it) } })
}
