package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/** 2.1 事实校对之后：逐条修、差异、快照还原、风格把关（纯逻辑，便于单测） */
object FactFix {
    data class Snap(val title: String, val body: String, val at: Long)
    data class Edit(val claimId: String, val old: String, val new: String)
    /** 风格把关建议；state: ""=待处理 / applied / ignored */
    data class Tip(val id: String, val kind: String, val para: Int, val why: String, val old: String, val new: String,
                   val state: String = "", val alts: List<String> = emptyList())

    const val APPLIED = "applied"; const val IGNORED = "ignored"; const val MANUAL = "manual"

    // ---------- 每条说法的状态 ----------
    private val rank = listOf(FactPipeline.BAD, FactPipeline.PART, FactPipeline.UNK, FactPipeline.OK)
    /** 一条说法的「最差」结论（只看硬事实细节） */
    fun verdictOf(c: FactClaim): String {
        val vs = c.details.filter { it.type != FactPipeline.T_OPINION && it.type != FactPipeline.T_RHET }.map { it.verdict }
        return rank.firstOrNull { it in vs } ?: if (vs.isEmpty()) FactPipeline.OK else FactPipeline.UNK
    }
    fun isProblem(c: FactClaim) = c.done && verdictOf(c) in listOf(FactPipeline.BAD, FactPipeline.PART, FactPipeline.UNK)
    fun pending(c: FactClaim) = isProblem(c) && c.fix.isBlank()
    /** 说明问题用的那条细节 */
    fun keyDetail(c: FactClaim): FactDetail? { val v = verdictOf(c); return c.details.firstOrNull { it.verdict == v } ?: c.details.firstOrNull() }
    /** 带证据链接的那条（无法核实时可能没有） */
    fun evidence(c: FactClaim): FactDetail? = keyDetail(c)?.takeIf { it.quote.isNotBlank() && it.url.isNotBlank() }
        ?: c.details.firstOrNull { it.quote.isNotBlank() && it.url.isNotBlank() && it.verdict != FactPipeline.OK }

    /** 默认建议：错的给最小改写，无法核实给稳妥说法 */
    fun suggestion(c: FactClaim): String {
        if (c.fixText.isNotBlank()) return c.fixText
        val v = verdictOf(c)
        // 本地兜底只给完整的句子；「民国初年」这种短语前面加「据报道」读不通
        if (v == FactPipeline.UNK) return c.details.firstOrNull { it.soft.isNotBlank() }?.soft ?: if (c.span.trim().length >= 10) soften(c.span) else ""
        val rw = c.details.filter { it.verdict == FactPipeline.BAD || it.verdict == FactPipeline.PART }.firstOrNull { it.rewrite.isNotBlank() }?.rewrite
        return rw ?: ""
    }

    private val numRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(万|亿|千|百)?\\s*(%|％|人|元|年|个|次|家|部|名|倍)?")
    /** 本地兜底的稳妥说法：已有「据」开头就把精确数字改成约数，否则加「据报道，」 */
    fun soften(span: String): String {
        val t = span.trim()
        if (t.isBlank()) return t
        if (t.startsWith("据") || t.startsWith("有报道")) {
            val m = numRe.find(t) ?: return t
            if (m.groupValues[2].isBlank() && m.groupValues[3].isBlank()) return t
            return t.replaceRange(m.range, "约" + m.value.trim())
        }
        return "据报道，$t"
    }

    // ---------- 差异 ----------
    data class Diff(val head: String, val old: String, val new: String, val tail: String)
    /** 字级最小差异：公共前缀 + 公共后缀之外的部分 */
    fun diff(a: String, b: String): Diff {
        var p = 0
        while (p < a.length && p < b.length && a[p] == b[p]) p++
        var q = 0
        while (q < a.length - p && q < b.length - p && a[a.length - 1 - q] == b[b.length - 1 - q]) q++
        // 不要把一个代理对劈开
        if (p > 0 && p < a.length && Character.isLowSurrogate(a[p])) p--
        return Diff(a.substring(0, p), a.substring(p, a.length - q), b.substring(p, b.length - q), a.substring(a.length - q))
    }
    /** 改动太大（几乎整句重写）就不算「最小改动」 */
    fun minimal(a: String, b: String): Boolean { val d = diff(a, b); return d.old.length + d.new.length <= maxOf(8, ((a.length + b.length) * 0.7).toInt()) }

    // ---------- 应用 / 还原 ----------
    data class Applied(val title: String, val body: String)
    /** 把原句换成新写法；原句已不在正文（她改过）返回 null */
    fun applyOne(title: String, body: String, old: String, new: String): Applied? {
        if (old.isBlank()) return null
        val i = body.indexOf(old)
        if (i >= 0) return Applied(title, body.replaceRange(i, i + old.length, new))
        val j = title.indexOf(old)
        if (j >= 0) return Applied(title.replaceRange(j, j + old.length, new), body)
        return null
    }

    fun withClaim(r: FactReport, c: FactClaim): FactReport =
        FactPipeline.withClaims(r, r.claims.map { if (it.id == c.id) c.copy(details = c.details.map { d -> d.copy(applied = c.fix == APPLIED) }) else it })

    /** 采纳一条：返回新标题正文 + 新报告（第一次采纳前自动存快照） */
    fun adopt(r: FactReport, c: FactClaim, title: String, body: String, text: String, now: Long = System.currentTimeMillis()): Pair<Applied, FactReport>? {
        val a = applyOne(title, body, c.span, text) ?: return null
        val snap = r.snap ?: Snap(title, body, now)
        val r2 = withClaim(r, c.copy(fix = APPLIED, fixText = text)).copy(snap = snap, edits = r.edits.filter { it.claimId != c.id } + Edit(c.id, c.span, text))
        return a to r2
    }

    fun restore(r: FactReport): Pair<Applied, FactReport>? {
        val s = r.snap ?: return null
        val cs = r.claims.map { c -> (if (c.fix == APPLIED || c.fix == MANUAL) c.copy(fix = "") else c).copy(details = c.details.map { it.copy(applied = false) }) }
        return Applied(s.title, s.body) to FactPipeline.withClaims(r, cs).copy(snap = null, edits = emptyList(), style = emptyList(), styleRan = false)
    }

    // ---------- 段落 ----------
    fun paragraphs(body: String): List<String> = body.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    fun paraOf(body: String, text: String): Int = paragraphs(body).indexOfFirst { it.contains(text.trim()) } + 1
    /** 改过的段落 + 前后各一段（1 起） */
    fun styleScope(body: String, edits: List<Edit>): List<Int> {
        val ps = paragraphs(body)
        val hit = edits.mapNotNull { e -> ps.indexOfFirst { it.contains(e.new.trim().take(40)) }.takeIf { it >= 0 } }.toSet()
        return hit.flatMap { listOf(it - 1, it, it + 1) }.filter { it in ps.indices }.distinct().sorted().map { it + 1 }
    }

    // ---------- 换个改法 ----------
    const val ALT_SYS = "你是中文编辑，只做最小改动。只输出 JSON。" + Prompts22.NO_FAB_LINE
    fun altPrompt(span: String, problem: String, evidence: String, current: String) = """原句：$span
问题：$problem
资料原文：${evidence.ifBlank { "（没有查到资料）" }}
现有改法：$current

再给 2 种不同的改法：都必须对原句做最小改动，只改有问题的字词，句式、语气和作者的口吻保持原样；不得写进资料里没有的新事实。
输出：{"alts":["改法1","改法2"]}"""
    fun parseAlts(reply: String, span: String, current: String): List<String> {
        val j = runCatching { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) }.getOrNull() ?: return emptyList()
        val a = j.optJSONArray("alts") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() && it != span && it != current }.distinct().take(2)
    }

    // ---------- 风格把关 ----------
    val TIP_KINDS = listOf("论点", "衔接", "语气")
    const val STYLE_SYS = "你是一位克制的中文编辑：只看改过的地方，给最少、最必要的建议。只输出 JSON。" + Prompts22.NO_FAB_LINE
    fun stylePrompt(title: String, body: String, edits: List<Edit>, scope: List<Int>): String {
        val ps = paragraphs(body)
        val block = scope.joinToString("\n") { "[第 $it 段] ${ps[it - 1]}" }
        val ch = edits.joinToString("\n") { "· 「${it.old}」→「${it.new}」" }
        return """这篇文章刚按事实核查改了几处。请只检查下面这些段落（改过的段落和前后段），找出：
1. 论点：改了事实以后，原来的论证或结论还站得住吗？被削弱的地方要怎么收。
2. 衔接：改动处和上下文的过渡是否别扭。
3. 语气：改动处是否和全文口吻不一致，有没有 AI 腔（套话、排比堆砌、"值得注意的是"之类）。
没有问题就不要硬给；最多 5 条。old 必须是段落里一字不差的片段，new 是对它的最小改动。
输出：{"tips":[{"kind":"论点|衔接|语气","para":3,"why":"一句话说明","old":"原文片段","new":"改后"}]}

标题：$title
全文开头（看口吻用）：${body.take(500)}

改动：
$ch

要检查的段落：
$block"""
    }
    fun parseTips(reply: String, body: String): List<Tip> {
        val j = runCatching { JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)) }.getOrNull() ?: return emptyList()
        val a = j.optJSONArray("tips") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val old = o.optString("old").trim(); val new = o.optString("new").trim()
            if (old.isBlank() || new.isBlank() || old == new || !body.contains(old)) return@mapNotNull null
            val kind = o.optString("kind").let { k -> TIP_KINDS.firstOrNull { k.contains(it) } ?: "语气" }
            Tip("", kind, paraOf(body, old).takeIf { it > 0 } ?: o.optInt("para"), o.optString("why").trim(), old, new)
        }.distinctBy { it.old }.take(5).mapIndexed { i, t -> t.copy(id = "s${i + 1}") }
    }

    // ---------- JSON ----------
    fun encodeExtra(r: FactReport, j: JSONObject) {
        r.triage?.let { j.put("triage", JSONObject().put("facts", it.facts).put("opinions", it.opinions).put("descriptions", it.descriptions)) }
        r.snap?.let { j.put("snap", JSONObject().put("title", it.title).put("body", it.body).put("at", it.at)) }
        j.put("edits", JSONArray().apply { r.edits.forEach { put(JSONObject().put("claimId", it.claimId).put("old", it.old).put("new", it.new)) } })
        j.put("style", JSONArray().apply { r.style.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind).put("para", it.para).put("why", it.why).put("old", it.old).put("new", it.new).put("state", it.state).put("alts", JSONArray(it.alts))) } })
        j.put("styleRan", r.styleRan)
    }
    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    /** 1.17 以前的报告只有 issues：转成说法，好在新列表里显示 */
    fun legacyClaims(issues: List<FactIssue>): List<FactClaim> = issues.filter { it.span.isNotBlank() }.map { i ->
        val v = when { i.verdict.contains("无法") -> FactPipeline.UNK; i.verdict.contains("部分") -> FactPipeline.PART; i.verdict.contains("印证") || i.verdict.contains("一致") -> FactPipeline.OK; else -> FactPipeline.BAD }
        val l = i.links.firstOrNull()
        FactClaim(i.id, i.span, i.claim, listOf(FactDetail(i.id, i.category, i.claim, verdict = v, quote = i.quote, url = l?.url.orEmpty(), sourceTitle = l?.title.orEmpty(),
            via = l?.via.orEmpty(), reason = i.note, rewrite = i.aiCorrection.ifBlank { i.correction }, applied = i.applied)), done = true, fix = if (i.applied) APPLIED else "")
    }
    fun decodeExtra(j: JSONObject, r: FactReport): FactReport = r.copy(
        claims = r.claims.ifEmpty { legacyClaims(r.issues) },
        triage = j.optJSONObject("triage")?.let { FactTriage(it.optInt("facts"), it.optInt("opinions"), it.optInt("descriptions")) },
        snap = j.optJSONObject("snap")?.let { Snap(it.optString("title"), it.optString("body"), it.optLong("at")) },
        edits = j.optJSONArray("edits")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Edit(it.optString("claimId"), it.optString("old"), it.optString("new")) } } ?: emptyList(),
        style = j.optJSONArray("style")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Tip(it.optString("id"), it.optString("kind"), it.optInt("para"), it.optString("why"), it.optString("old"), it.optString("new"), it.optString("state"), strs(it.optJSONArray("alts"))) } } ?: emptyList(),
        styleRan = j.optBoolean("styleRan"))
}
