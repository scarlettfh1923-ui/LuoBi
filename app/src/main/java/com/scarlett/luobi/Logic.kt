package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 意图变量机制（需求说明 3.5 / 3.7）：纯逻辑，便于单元测试。
 * - 变量写成 {名字}；名字来自当前清洗模板的输出字段，另有系统变量 {原文}。
 * - 生成时把值原样替换进去；某行引用的变量全部为空时，这一行整行去掉。
 */
object Names {
    /** 模板名去重：与已有名字（不含自己）重复时加「 2」「 3」… */
    fun unique(want: String, taken: Collection<String>): String {
        val base = want.trim().ifBlank { "未命名模板" }
        if (base !in taken) return base
        var i = 2; while ("$base $i" in taken) i++; return "$base $i"
    }
}

object Vars {
    const val RAW = "原文"
    val DEFAULT_FIELDS = listOf("疑问", "观点", "想写什么")
    private val re = Regex("\\{([\\p{IsHan}\\w]{1,20})\\}")

    /** 模板里引用到的变量名（按出现顺序、去重） */
    fun referenced(prompt: String): List<String> = re.findAll(prompt).map { it.groupValues[1].trim() }.distinct().toList()

    /** 写作模板引用了、但当前清洗模板不输出的变量（{原文} 永远可用） */
    fun missing(prompt: String, fields: List<String>): List<String> =
        referenced(prompt).filter { it != RAW && it !in fields }

    /**
     * 1.4 填值规则（从不删除用户写的句子）：
     * - 有值 → 原样替换；连在一起的多个占位（如 {疑问}{观点}{想写什么}）只拼有值的，用「；」隔开；
     * - 已知字段但为空 → 只去掉这个占位，所在句子保留；
     * - 未知变量（不是清洗字段，如 {读者}）→ 原样保留，预览里标黄；
     * - 唯一例外：纯「标签：{变量}」行（如「我的观点：{观点}」）全部为空时整行省略，避免留下孤零零的「我的观点：」。
     * 1.3 是「一行里任一变量为空就整行删」，她模板里「创作的主题围绕{疑问}{观点}{想写什么}，你要写一篇……默认要求：」整行因此消失。
     */
    private val run = Regex("(?:\\{[\\p{IsHan}\\w]{1,20}\\}\\s*)+")
    private val labelRe = Regex("^\\s*[^。！？!?；;，,{}]{0,12}[：:]?\\s*$")
    fun isLabelLine(line: String): Boolean = line.contains(re) && labelRe.matches(re.replace(line, "").replace(Regex("[/、|\\s]"), ""))
    val defaultKnown get() = (DEFAULT_FIELDS + RAW).toSet()
    private fun has(values: Map<String, String>, k: String) = !values[k].isNullOrBlank()
    private fun dropLine(line: String, values: Map<String, String>, known: Set<String>): Boolean {
        if (!isLabelLine(line)) return false
        val used = re.findAll(line).map { it.groupValues[1].trim() }.toList()
        return used.none { has(values, it) || (it !in known && it !in values) }
    }
    /** 一行 → [文本, 种类] 片段；种类 0=原文 1=填入的值 2=未知变量（原样） */
    fun lineSegs(line: String, values: Map<String, String>, known: Set<String>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(); var last = 0
        for (m in run.findAll(line)) {
            if (m.range.first > last) out += line.substring(last, m.range.first) to 0
            val trail = m.value.length - m.value.trimEnd().length
            val parts = re.findAll(m.value).toList()
            var first = true
            for (pm in parts) {
                val k = pm.groupValues[1].trim()
                when {
                    has(values, k) -> { if (!first) out += "；" to 0; out += values[k]!! to 1; first = false }
                    k !in known && k !in values -> { if (!first) out += "；" to 0; out += pm.value to 2; first = false }
                }
            }
            if (trail > 0) out += m.value.takeLast(trail) to 0
            last = m.range.last + 1
        }
        if (last < line.length) out += line.substring(last) to 0
        return out
    }
    fun segmentsK(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown): List<Pair<String, Int>> {
        val lines = prompt.replace("\r\n", "\n").split("\n").filterNot { dropLine(it, values, known) }
        val res = ArrayList<Pair<String, Int>>()
        lines.forEachIndexed { i, l -> res += lineSegs(l, values, known); if (i < lines.lastIndex) res += "\n" to 0 }
        return res
    }
    fun fill(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown): String =
        segmentsK(prompt, values, known).joinToString("") { it.first }
    /** 预览高亮用：返回 [文本, 是否是替换进来的值]；与 fill 同规则 */
    fun segments(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown): List<Pair<String, Boolean>> =
        segmentsK(prompt, values, known).filter { it.first.isNotEmpty() }.map { it.first to (it.second == 1) }
    /** 未知变量（会原样留在提示词里） */
    fun unknown(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown) = referenced(prompt).filter { it !in known && it !in values }

    /** 被删掉的空变量（提示用户「{观点} 为空，那一行已去掉」） */
    fun emptyUsed(prompt: String, values: Map<String, String>): List<String> =
        referenced(prompt).filter { values[it].isNullOrBlank() }
    /** 因变量为空被整行删掉的行数（只算标签行） */
    fun droppedLines(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown): Int = prompt.replace("\r\n", "\n").split("\n").count { dropLine(it, values, known) }

    /** 清洗模板的系统变量 */
    fun fillClean(prompt: String, raw: String, templateList: String) =
        prompt.replace("{模板列表}", templateList).replace("{原始文本}", raw)
}

data class CleanResult(val cleaned: String, val values: LinkedHashMap<String, String>, val recommend: String)

/** 清洗结果 JSON 容错解析 */
object CleanParse {
    class BadFormat(val raw: String) : Exception("清洗结果格式不对")

    fun parse(text: String, fields: List<String>): CleanResult {
        val j = lenient(text) ?: throw BadFormat(text.take(300))
        val cleaned = str(j.opt("cleaned")).ifBlank { str(j.opt("清洗后文字")) }.ifBlank { str(j.opt("text")) }
        if (cleaned.isBlank()) throw BadFormat(text.take(300))
        val values = LinkedHashMap<String, String>()
        for (f in fields) { val v = str(j.opt(f)).trim(); if (v.isNotBlank() && v != "null" && v != "无" && v != "……") values[f] = v }
        return CleanResult(cleaned.trim(), values, str(j.opt("recommend")).trim().trim('「', '」', '"', '《', '》'))
    }

    private fun str(o: Any?): String = when (o) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until o.length()).joinToString("；") { o.optString(it) }
        else -> o.toString()
    }

    /** 去掉 <think>、```json 围栏，取第一个括号配平的 {...} */
    fun lenient(sIn: String): JSONObject? {
        var s = sIn.trim().removePrefix("\uFEFF")
        s = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
        try { return JSONObject(s) } catch (_: Exception) {}
        val a = s.indexOf('{'); if (a < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in a until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return try { JSONObject(s.substring(a, i + 1)) } catch (_: Exception) { null } } }
        }
        // 被截断：尝试补齐
        return try { JSONObject(s.substring(a) + (if (inStr) "\"" else "") + "}".repeat(depth.coerceAtLeast(1))) } catch (_: Exception) { null }
    }

    /** 推荐模板名 → 模板（完全相同 > 包含） */
    fun <T> match(recommend: String, list: List<T>, name: (T) -> String): T? {
        if (recommend.isBlank()) return null
        return list.firstOrNull { name(it) == recommend } ?: list.firstOrNull { recommend.contains(name(it)) || name(it).contains(recommend) }
    }
    /** 同名时优先「我的」模板（mine=true），再按名字包含 */
    fun <T> matchPrefer(recommend: String, list: List<T>, name: (T) -> String, mine: (T) -> Boolean): T? {
        val r = recommend.trim().removePrefix("「").removeSuffix("」"); if (r.isBlank()) return null
        val exact = list.filter { name(it).trim() == r }
        return exact.firstOrNull(mine) ?: exact.firstOrNull() ?: match(r, list.sortedByDescending(mine), name)
    }
}

data class Article(val title: String, val body: String, val tags: List<String>)

/** 写作结果：优先读 JSON {title, body, tags}，失败时把纯文本当正文 */
object ArticleParse {
    fun parse(text: String, fallbackTitle: String): Article {
        val j = CleanParse.lenient(text)
        if (j != null && j.optString("body").isNotBlank()) {
            val ta = j.optJSONArray("tags")
            val tags = if (ta != null) (0 until ta.length()).map { ta.optString(it) } else j.optString("tags").split(" ", ",", "，")
            return Article(j.optString("title").trim().ifBlank { fallbackTitle }, j.optString("body").trim(), cleanTags(tags))
        }
        var t = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        Regex("```(?:markdown|md)?\\s*([\\s\\S]*?)```").find(t)?.let { t = it.groupValues[1].trim() }
        val lines = t.lines().toMutableList()
        val tagLine = lines.lastOrNull { it.trim().startsWith("#") && !it.trim().startsWith("# ") && it.contains("#") && it.trim().split(Regex("\\s+")).all { w -> w.startsWith("#") } }
        val tags = tagLine?.trim()?.split(Regex("\\s+"))?.let { cleanTags(it) } ?: emptyList()
        if (tagLine != null) lines.remove(tagLine)
        val first = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = first.trimStart('#', ' ').removePrefix("标题：").removePrefix("标题:").trim().take(60)
        val body = if (first.isNotEmpty() && first.length <= 40) lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim() else lines.joinToString("\n").trim()
        return Article(title.ifBlank { fallbackTitle }, body.ifBlank { t }, tags)
    }
    fun cleanTags(l: List<String>) = l.map { it.trim().trimStart('#').trim() }.filter { it.isNotEmpty() && it.length <= 20 }.distinct().take(10)
}

/** 导出：Markdown / HTML（正文里以 ## 或 isHead 规则识别小标题） */
object Export {
    private fun heading(p: String, total: Int): String? {
        val t = p.trim()
        if (t.startsWith("#")) return t.trimStart('#').trim()
        if (t.startsWith("**") && t.endsWith("**") && t.length < 40) return t.trim('*').trim()
        return if (isHeadLine(t, total)) t else null
    }
    fun paras(body: String) = body.replace("\r", "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    fun markdown(title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList(), showCredit: Boolean = true): String {
        val ps = paras(body); val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val sb = StringBuilder("# ").append(title).append("\n\n")
        ps.forEachIndexed { i, p -> sb.append(Md.mdLine(Md.line(p, ps.size))).append("\n\n")
            bp[i]?.forEach { pic -> sb.append("![").append(pic.caption.replace("]", "")).append("](").append(pic.url).append(")\n")
                val cap = pic.captionLine(showCredit); if (cap.isNotBlank()) sb.append("*").append(cap).append("*\n"); sb.append("\n") } }
        if (tags.isNotEmpty()) sb.append(tags.joinToString(" ") { "#$it" }).append("\n")
        return sb.toString().trimEnd() + "\n"
    }
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun inline(s: String) = Md.spans(s).joinToString("") { (t, b) -> if (b) "<strong>" + esc(t) + "</strong>" else esc(t) }
    /** 阅读排版 A 的 HTML（富文本复制 / .html / 发布接口共用） */
    fun htmlLine(l: MdLine, accent: String = "#2F6BFF"): String = when (l.kind) {
        MdKind.H2 -> "<h2 style=\"font-size:20px;font-weight:700;line-height:1.45;margin:28px 0 10px;color:#111827;\">${inline(l.text)}</h2>"
        MdKind.H3 -> "<h3 style=\"font-size:17px;font-weight:700;margin:20px 0 8px;color:#1F2937;\">${inline(l.text)}</h3>"
        MdKind.QUOTE -> "<blockquote style=\"border-left:3px solid #CBD5E1;background:#F1F4F8;padding:10px 14px;color:#5B6576;margin:16px 0;border-radius:0 6px 6px 0;\">${inline(l.text)}</blockquote>"
        MdKind.BULLET -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 8px;padding-left:1.2em;text-indent:-1.2em;color:#1F2937;\">•&nbsp;&nbsp;${inline(l.text)}</p>"
        MdKind.NUM -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 8px;padding-left:1.4em;text-indent:-1.4em;color:#1F2937;\">${esc(l.num)}.&nbsp;${inline(l.text)}</p>"
        MdKind.HR -> "<hr style=\"border:0;border-top:1px solid #E3E8EF;margin:24px 0;\">"
        MdKind.P -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 18px;color:#1F2937;\">${inline(l.text)}</p>"
    }
    fun htmlBody(body: String, tags: List<String>, accent: String = "#2F6BFF", pics: List<Pic> = emptyList(), showCredit: Boolean = true): String {
        val ps = paras(body); val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val sb = StringBuilder()
        ps.forEachIndexed { i, p ->
            sb.append(htmlLine(Md.line(p, ps.size), accent)).append("\n")
            bp[i]?.forEach { pic -> sb.append(figure(pic, showCredit)) }
        }
        if (tags.isNotEmpty()) sb.append("<p style=\"color:$accent;font-size:14px;\">").append(tags.joinToString(" ") { "#" + esc(it) }).append("</p>\n")
        return sb.toString()
    }
    fun figure(pic: Pic, showCredit: Boolean = true): String {
        val sb = StringBuilder("<figure style=\"margin:16px 0 18px;\"><img src=\"").append(esc(pic.url)).append("\" alt=\"").append(esc(pic.caption)).append("\" style=\"width:100%;border-radius:10px;display:block;\">")
        val cap = pic.captionLine(showCredit)
        if (cap.isNotEmpty()) sb.append("<figcaption style=\"font-size:11px;color:#A0A8B6;text-align:center;margin-top:6px;\">").append(esc(cap)).append("</figcaption>")
        return sb.append("</figure>\n").toString()
    }
    fun htmlDoc(title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList(), showCredit: Boolean = true): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>body{margin:0;background:#F5F7FA;font-family:-apple-system,"Noto Sans SC","Source Han Sans SC","PingFang SC",sans-serif;color:#111827}
article{max-width:680px;margin:0 auto;padding:40px 22px 60px;background:#fff;font-family:"Source Han Serif SC","Noto Serif CJK SC","Songti SC",serif}h1{font-size:26px;line-height:1.4;margin:0 0 18px}</style></head>
<body><article><h1>${esc(title)}</h1>
${htmlBody(body, tags, pics = pics, showCredit = showCredit)}</article></body></html>
"""
    fun plain(title: String, body: String, tags: List<String>) = title + "\n\n" + body + if (tags.isEmpty()) "" else "\n\n" + tags.joinToString(" ") { "#$it" }

    /** 自定义渠道 JSON 模板：{title}{body}{tags} 以 JSON 字符串转义后替换 */
    fun jsonTpl(tpl: String, title: String, body: String, tags: List<String>): String {
        fun q(s: String) = JSONObject.quote(s).let { it.substring(1, it.length - 1) }
        return tpl.replace("{title}", q(title)).replace("{body}", q(body)).replace("{tags}", q(tags.joinToString(",")))
    }

    /** 与 Ui.isHead 一致（拷贝到这里方便纯 JVM 测试） */
    fun isHeadLine(p: String, total: Int): Boolean {
        if (p.length > 24 || p.contains('。') || total <= 3 || p.isEmpty()) return false
        val c = p.codePointAt(0)
        val emoji = Character.isSupplementaryCodePoint(c) || Character.getType(c) == Character.OTHER_SYMBOL.toInt()
        return emoji || p[0].isDigit() || p.startsWith("【") || Regex("^[一二三四五六七八九十]+[、.．]").containsMatchIn(p) || p.endsWith("：") || p.endsWith(":")
    }
}

/** 篇幅：短 / 中 / 长 各自的最少–最多字数 */
object Lengths {
    private val own = Regex("(?m)^\\s*(篇幅|字数|全文字数|长度)\\s*[：:]")
    /** 模板里自己写了「篇幅：…」行 → 以模板为准，不再追加设置里的篇幅 */
    fun inTemplate(prompt: String) = own.containsMatchIn(prompt)
    val NAMES = listOf("短", "中", "长")
    val DEFAULT = listOf(150 to 300, 400 to 800, 1000 to 1800)
    /** 校验输入；合法返回 null，否则返回提示 */
    fun validate(min: String, max: String): String? {
        val a = min.trim().toIntOrNull(); val b = max.trim().toIntOrNull()
        if (a == null || b == null || a <= 0 || b <= 0) return "请填正整数"
        if (a > 99999 || b > 99999) return "字数太大了"
        if (a > b) return "最少不能大于最多"
        return null
    }
    fun encode(l: List<Pair<Int, Int>>) = l.joinToString(",") { "${it.first}-${it.second}" }
    fun decode(s: String): List<Pair<Int, Int>> {
        val r = s.split(",").mapNotNull { p -> val x = p.split("-"); val a = x.getOrNull(0)?.trim()?.toIntOrNull(); val b = x.getOrNull(1)?.trim()?.toIntOrNull(); if (a != null && b != null && a in 1..b) a to b else null }
        return if (r.size == 3) r else DEFAULT
    }
    fun hint(i: Int, r: Pair<Int, Int>) = "篇幅：${NAMES[i.coerceIn(0, 2)]}篇，正文字数控制在 ${r.first}–${r.second} 字之间。"
    fun label(i: Int, r: Pair<Int, Int>) = "${NAMES[i.coerceIn(0, 2)]}（${r.first}–${r.second} 字）"
}

/** 正文的轻量 Markdown：一行（Export.paras 的一项）= 一个块，插图按同样的序号定位 */
enum class MdKind { H2, H3, P, QUOTE, BULLET, NUM, HR }
data class MdLine(val kind: MdKind, val text: String, val num: String = "")

object Md {
    private val zh = "零一二三四五六七八九十"
    /** 「1」「01」「一」「1.」「第一部分」这类纯序号标题 → 序号；否则 null */
    fun sectionNo(t: String): Int? {
        val x = t.replace("**", "").trim().removePrefix("第").removeSuffix("部分").removeSuffix("节").trimEnd('.', '、', '．', ' ')
        x.toIntOrNull()?.let { return it.takeIf { n -> n in 0..99 } }
        if (x.length == 1 && x[0] in zh) return zh.indexOf(x[0])
        if (x.length == 2 && x[0] == '十' && x[1] in zh) return 10 + zh.indexOf(x[1])
        return null
    }
    private val numRe = Regex("^(\\d{1,3})[.、．)]\\s*(.+)$")
    fun line(raw: String, total: Int): MdLine {
        val p = raw.trim()
        if (Regex("^([-*_])(\\s*\\1){2,}$").matches(p)) return MdLine(MdKind.HR, "")
        if (p.startsWith("###")) return MdLine(MdKind.H3, p.trimStart('#').trim())
        if (p.startsWith("#")) return MdLine(MdKind.H2, p.trimStart('#').trim())
        if (p.startsWith(">")) return MdLine(MdKind.QUOTE, p.trimStart('>', ' ', '　').trim())
        if (p.startsWith("- ") || p.startsWith("* ") || p.startsWith("• ") || p.startsWith("· ")) return MdLine(MdKind.BULLET, p.substring(2).trim())
        numRe.find(p)?.let { m -> if (p.length > 24 || p.contains('。') || total <= 3) return MdLine(MdKind.NUM, m.groupValues[2].trim(), m.groupValues[1]) }
        if (p.startsWith("**") && p.endsWith("**") && p.length in 5..42 && !p.substring(2, p.length - 2).contains("**")) return MdLine(MdKind.H2, p.substring(2, p.length - 2).trim())
        if (Export.isHeadLine(p, total)) return MdLine(MdKind.H2, p)
        return MdLine(MdKind.P, p)
    }
    /** 行内：**粗体**；返回 (文字, 是否加粗) 片段，未闭合的 ** 原样保留 */
    fun spans(t: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>(); var i = 0
        while (i < t.length) {
            val a = t.indexOf("**", i)
            if (a < 0) { out += t.substring(i) to false; break }
            val b = t.indexOf("**", a + 2)
            if (b < 0 || b == a + 2) { out += t.substring(i) to false; break }
            if (a > i) out += t.substring(i, a) to false
            out += t.substring(a + 2, b) to true; i = b + 2
        }
        return out.filter { it.first.isNotEmpty() }
    }
    fun strip(t: String) = spans(t).joinToString("") { it.first }
    /** 纯文本（复制全文 / 抖音）：去掉标记，列表用 •，引用用「」 */
    fun plainLine(l: MdLine) = when (l.kind) {
        MdKind.HR -> "——"; MdKind.BULLET -> "• " + strip(l.text); MdKind.NUM -> l.num + ". " + strip(l.text)
        MdKind.QUOTE -> "「" + strip(l.text) + "」"; else -> strip(l.text)
    }
    fun plainBody(body: String): String { val ps = Export.paras(body); return ps.joinToString("\n\n") { plainLine(line(it, ps.size)) } }
    fun mdLine(l: MdLine) = when (l.kind) {
        MdKind.H2 -> "## " + l.text; MdKind.H3 -> "### " + l.text; MdKind.QUOTE -> "> " + l.text; MdKind.BULLET -> "- " + l.text
        MdKind.NUM -> l.num + ". " + l.text; MdKind.HR -> "---"; MdKind.P -> l.text
    }
}

/** 分享栏：最多 5 个渠道 + 固定最右的「更多」 */
object ShareBar {
    const val MAX = 5
    const val MORE = "more"
    val DEFAULT = listOf("xhs", "wechat", "qq", "douyin", "notion")
    /** 清洗：去重、去掉 more 和不认识的、截到 5 个；允许少于 5 个，允许空 */
    fun normalize(l: List<String>, known: Collection<String>): List<String> = l.map { it.trim() }.filter { it.isNotEmpty() && it != MORE && (it in known || Regex("c\\d+").matches(it)) }.distinct().take(MAX)
    fun encode(l: List<String>) = l.joinToString(",")
    fun decode(s: String?, known: Collection<String>): List<String> = if (s == null) DEFAULT.filter { it in known } else normalize(s.split(","), known)
    fun add(l: List<String>, id: String): List<String> = if (id in l || id == MORE || l.size >= MAX) l else l + id
    fun remove(l: List<String>, id: String) = l - id
    fun move(l: List<String>, from: Int, to: Int): List<String> {
        if (from !in l.indices) return l
        return l.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }
    }
    /** 栏里显示的格子：所选 + 更多 */
    fun tiles(l: List<String>) = l + MORE
    /** 「更多」面板：所有不在栏里的渠道，保持原顺序 */
    fun others(all: List<String>, bar: List<String>) = all.filter { it !in bar && it != MORE }
}
