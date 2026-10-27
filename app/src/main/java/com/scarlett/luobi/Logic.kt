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
    /** 1.9：她的模板正文用这个——一行都不删，空占位只去掉占位本身 */
    fun fillKeep(prompt: String, values: Map<String, String>, known: Set<String> = defaultKnown): String =
        prompt.replace("\r\n", "\n").split("\n").joinToString("\n") { l -> lineSegs(l, values, known).joinToString("") { it.first } }
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
        // 1.9：不再要求 cleaned（意图提取只输出三项意图 + recommend）；旧提示词带 cleaned 也兼容
        val cleaned = str(j.opt("cleaned")).ifBlank { str(j.opt("清洗后文字")) }.ifBlank { str(j.opt("text")) }
        val values = LinkedHashMap<String, String>()
        for (f in fields) { val v = str(j.opt(f)).trim(); if (v.isNotBlank() && v != "null" && v != "无" && v != "……") values[f] = v }
        if (values.isEmpty() && cleaned.isBlank() && !j.has("recommend")) throw BadFormat(text.take(300))
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

data class Article(val title: String, val body: String, val tags: List<String>, val category: String = "")

/** 写作结果：优先读 JSON {title, body, tags}，失败时把纯文本当正文 */
object ArticleParse {
    fun parse(text: String, fallbackTitle: String): Article = clean(raw(text, fallbackTitle))
    /** 2.2：所有结果都过 Sanitize；正文仍像 JSON 时再抠一次字段，绝不把原始 JSON 给用户看 */
    private fun clean(a: Article): Article {
        var body = a.body; var title = a.title; var tags = a.tags; var cat = a.category
        if (Salvage.looksJson(body)) {
            Salvage.field(body, "body")?.takeIf { it.isNotBlank() }?.let { b ->
                title = Salvage.field(body, "title")?.takeIf { it.isNotBlank() } ?: title
                if (tags.isEmpty()) tags = cleanTags(Salvage.tags(body)); if (cat.isBlank()) cat = Categories.validate(Salvage.field(body, "category").orEmpty())
                body = b }
            if (Salvage.looksJson(body)) body = body.replace(Regex("\"(?:title|body|tags|category)\"\\s*:\\s*"), "").replace(Regex("[{}\\[\\]]"), "").replace("\\n", "\n")
        }
        val t = Sanitize.title(title).ifBlank { a.title }
        return Article(t, Sanitize.body(body, t), tags, cat)
    }
    private fun raw(text: String, fallbackTitle: String): Article {
        val j = CleanParse.lenient(text)
        if (j != null && j.optString("body").isNotBlank()) {
            val ta = j.optJSONArray("tags")
            val tags = if (ta != null) (0 until ta.length()).map { ta.optString(it) } else j.optString("tags").split(" ", ",", "，")
            return Article(j.optString("title").trim().ifBlank { fallbackTitle }, j.optString("body").trim(), cleanTags(tags), Categories.validate(j.optString("category")))
        }
        // JSON 坏了（没转义的引号 / 截断）：按字段抠
        if (Regex("\"body\"\\s*[:：]\\s*\"").containsMatchIn(text)) {
            val body = Salvage.field(text, "body").orEmpty()
            if (body.isNotBlank()) return Article(Salvage.field(text, "title")?.trim()?.ifBlank { null } ?: fallbackTitle, body.trim(),
                cleanTags(Salvage.tags(text)), Categories.validate(Salvage.field(text, "category").orEmpty()))
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
    fun htmlLine(l: MdLine, accent: String = "#2E5E6B"): String = when (l.kind) {
        MdKind.H2 -> "<h2 style=\"font-size:20px;font-weight:700;line-height:1.45;margin:28px 0 10px;color:#1E1B17;\">${inline(l.text)}</h2>"
        MdKind.H3 -> "<h3 style=\"font-size:17px;font-weight:700;margin:20px 0 8px;color:#2E2923;\">${inline(l.text)}</h3>"
        MdKind.QUOTE -> "<blockquote style=\"border-left:3px solid #D3C8B6;background:#F2ECE1;padding:10px 14px;color:#5C5348;margin:16px 0;border-radius:0 6px 6px 0;\">${inline(l.text)}</blockquote>"
        MdKind.BULLET -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 8px;padding-left:1.2em;text-indent:-1.2em;color:#2E2923;\">•&nbsp;&nbsp;${inline(l.text)}</p>"
        MdKind.NUM -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 8px;padding-left:1.4em;text-indent:-1.4em;color:#2E2923;\">${esc(l.num)}.&nbsp;${inline(l.text)}</p>"
        MdKind.HR -> "<hr style=\"border:0;border-top:1px solid #E5DDCF;margin:24px 0;\">"
        MdKind.P -> "<p style=\"font-size:16px;line-height:1.8;margin:0 0 18px;color:#2E2923;\">${inline(l.text)}</p>"
    }
    fun htmlBody(body: String, tags: List<String>, accent: String = "#2E5E6B", pics: List<Pic> = emptyList(), showCredit: Boolean = true): String {
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
<style>body{margin:0;background:#F4F0E8;font-family:-apple-system,"Noto Sans SC","Source Han Sans SC","PingFang SC",sans-serif;color:#1E1B17}
article{max-width:680px;margin:0 auto;padding:40px 22px 60px;background:#FFFCF6;font-family:"Source Han Serif SC","Noto Serif CJK SC","Songti SC",serif}h1{font-size:26px;line-height:1.4;margin:0 0 18px}</style></head>
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
    const val HEAD_MAX = 14
    const val PUNCT = "。！？，；,;!?…"
    /** 像句子：有句读，且不只是末尾一个冒号；或超过 24 字 */
    fun sentenceLike(t: String): Boolean { val x = t.replace("**", "").trim(); return x.length > 24 || x.dropLast(1).any { it in "。！？；;!?" } || (x.length > HEAD_MAX && x.any { it in PUNCT }) }
    private val numRe = Regex("^(\\d{1,3})[.、．)]\\s*(.+)$")
    fun line(raw: String, total: Int): MdLine {
        val p = raw.trim()
        if (Regex("^([-*_])(\\s*\\1){2,}$").matches(p)) return MdLine(MdKind.HR, "")
        // 1.5：句子样的「标题」（带句读、较长）其实是金句 → 正文字号加粗，不放大
        if (p.startsWith("#")) { val t = p.trimStart('#').trim(); if (sentenceLike(t)) return MdLine(MdKind.P, "**" + t.replace("**", "") + "**") }
        if (p.startsWith("###")) return MdLine(MdKind.H3, p.trimStart('#').trim())
        if (p.startsWith("#")) return MdLine(MdKind.H2, p.trimStart('#').trim())
        if (p.startsWith(">")) return MdLine(MdKind.QUOTE, p.trimStart('>', ' ', '　').trim())
        if (p.startsWith("- ") || p.startsWith("* ") || p.startsWith("• ") || p.startsWith("· ")) return MdLine(MdKind.BULLET, p.substring(2).trim())
        numRe.find(p)?.let { m -> if (p.length > 24 || p.contains('。') || total <= 3) return MdLine(MdKind.NUM, m.groupValues[2].trim(), m.groupValues[1]) }
        // 整段加粗：只有很短、没有句读的才当小标题（1.0 旧格式）；其余是金句，保持正文字号加粗
        if (p.startsWith("**") && p.endsWith("**") && p.length >= 5 && !p.substring(2, p.length - 2).contains("**")) {
            val t = p.substring(2, p.length - 2).trim()
            return if (t.length <= HEAD_MAX && t.none { it in PUNCT }) MdLine(MdKind.H2, t) else MdLine(MdKind.P, p)
        }
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
    /** 列表摘要：跳过所有小标题（含纯序号「1」「01」）、分隔线，取正文第一句起 */
    fun snippet(body: String, max: Int = 80): String {
        val ps = Export.paras(body)
        return ps.map { line(it, ps.size) }.filter { it.kind != MdKind.H2 && it.kind != MdKind.H3 && it.kind != MdKind.HR && sectionNo(it.text) == null }
            .joinToString(" ") { plainLine(it) }.replace(Regex("\\s+"), " ").trim().take(max)
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


/**
 * 1.9 模板头：每个模板开头是【你是谁】+【用户的意图】，后面才是她自己的正文。
 * 迁移只在开头加头，正文逐字节保留；已有头就不动（幂等）。
 */
object TplHeader {
    const val ROLE = "【你是谁】"; const val INTENT = "【用户的意图】"
    val INTENT_LINES = listOf("想写什么：{想写什么}", "我的观点：{观点}", "我的疑惑：{疑问}")
    /** 1.14：意图行 = 短标签 + 只含意图占位（她的模板用「他的观点：」「用户的疑惑：」等） */
    private val intentLine = Regex("^(?:(想写什么|我的观点|我的疑惑)[：:].*|[^。！？{}：:]{0,8}[：:]\\s*(?:\\{(?:想写什么|观点|疑问)\\}\\s*)+)$")

    fun role(name: String): String = when (name) {
        "财经非虚构" -> "你是一名财经非虚构作者，擅长用真实数据和具体人物讲清一个经济现象。"
        "口播转文章" -> "你是一名资深编辑，擅长把口播整理成可以直接发布、保留原有语气的文章。"
        "观点表达" -> "你是一名评论作者，擅长亮明观点、讲清理由、回应不同意见。"
        "答疑解惑" -> "你是一名科普作者，擅长把一个问题讲明白：是什么、为什么、怎么办。"
        "小红书风格" -> "你是一名小红书博主，擅长写短段落、有钩子的图文笔记。"
        else -> "你是一名专业的中文写作者，擅长写「$name」类文章。"
    }
    fun header(name: String) = "$ROLE\n${role(name)}\n$INTENT\n" + INTENT_LINES.joinToString("\n") + "\n\n"
    fun has(text: String) = text.trimStart().startsWith(ROLE) && text.contains(INTENT)
    /** 迁移：没有头才加；正文原样接在头后面 */
    fun migrate(name: String, text: String): String = if (has(text)) text else header(name) + text

    /** 拆成 [头, 正文]；头 = 从开头到【用户的意图】后连续的意图行（及紧随的一个空行） */
    fun split(text: String): Pair<String, String>? {
        if (!has(text)) return null
        val lines = text.split("\n")
        val i = lines.indexOfFirst { it.trim() == INTENT }; if (i < 0) return null
        var j = i + 1
        while (j < lines.size && intentLine.matches(lines[j].trim())) j++
        val headEnd = if (j < lines.size && lines[j].isBlank()) j + 1 else j
        return lines.take(j).joinToString("\n") to lines.drop(headEnd).joinToString("\n")
    }
    /** 填头：意图行占位为空 → 整行省略（只对这些生成的头行）；三项全空 → 连【用户的意图】一起省略 */
    fun fillHeader(head: String, values: Map<String, String>): String {
        val lines = head.split("\n"); val i = lines.indexOfFirst { it.trim() == INTENT }
        val ph = Regex("\\{([\\p{IsHan}\\w]{1,20})\\}")
        val out = ArrayList<String>(); val kept = ArrayList<String>()
        lines.forEachIndexed { n, l ->
            if (n < i) out += Vars.fillKeep(l, values)
            else if (n > i) {
                val ks = ph.findAll(l).map { it.groupValues[1] }.toList()
                if (ks.isEmpty() || ks.any { !values[it].isNullOrBlank() }) kept += Vars.fillKeep(l, values)
            }
        }
        if (kept.isNotEmpty()) { out += INTENT; out += kept }
        return out.joinToString("\n").trimEnd()
    }
}

/**
 * 1.9 最终提示词：你是谁 → 用户的意图（空项整段省略）→ 你要创作什么（体裁 + 篇幅）→ 口播原文 → 她的模板（原样附上，占位照常填）。
 * 她模板里的文字一个字都不丢；模板里写了「篇幅：」就以模板为准，不再追加设置里的篇幅。
 */
object Compose {
    /** 意图卡三项：占位键 → 卡片上显示的名字 */
    val FIELDS = listOf("想写什么" to "想写什么", "观点" to "我的观点", "疑问" to "我的疑惑")
    private val NUM = listOf("一", "二", "三", "四", "五", "六")

    /**
     * 2.2：底座（user）= 这篇要表达什么 → 篇幅（始终用设置里的篇幅）→ 素材 → 这类文章怎么写（模板原文，占位照常填）→ 平台模块 → 自检。
     * 旧式模板（带【你是谁】【用户的意图】头）：头里的身份描述保留进「这类文章怎么写」，意图行由底座统一给出。
     */
    fun prompt(tplName: String, tplDesc: String, tplText: String, values: Map<String, String>, raw: String, length: Int,
               range: Pair<Int, Int> = Lengths.DEFAULT[length.coerceIn(0, 2)], known: Set<String> = Vars.defaultKnown, xhs: Boolean = false): String {
        val v = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val fv = v + (Vars.RAW to raw.trim())
        val secs = mutableListOf<String>()
        val intent = buildList {
            v["想写什么"]?.let { add("想写什么：$it") }
            v["观点"]?.let { add("用户的观点：$it\n→ 这是全文的立场。顺着它往深处写，让它更站得住；不要稀释成“各有道理”，也不要写成相反的意思。") }
            v["疑问"]?.let { add("用户的疑惑：$it\n→ 正文里要正面回应。") }
        }
        if (intent.isNotEmpty()) secs += "【这篇要表达什么】\n" + intent.joinToString("\n")
        secs += "【篇幅】" + Lengths.hint(length, range).removePrefix("篇幅：").trimEnd('。') + "（大约 ${(range.second / 110).coerceAtLeast(2)}–${(range.second / 80).coerceAtLeast(3)} 段，不要超过 ${range.second} 字）。宁可把一个点写透，也不要面面俱到。" +
            (if (Lengths.inTemplate(tplText)) "（下面写法里如果另有篇幅要求，以这里为准。）" else "")
        secs += "【素材：用户的口播原文】\n${Prompts22.SRC_NOTE}\n<<<\n${raw.trim()}\n>>>"
        val sp = TplHeader.split(tplText)
        val how = if (sp != null) {
            val role = sp.first.split("\n").let { ls -> val i = ls.indexOfFirst { it.trim() == TplHeader.ROLE }; val j = ls.indexOfFirst { it.trim() == TplHeader.INTENT }
                if (i >= 0 && j > i) ls.subList(i + 1, j).joinToString("\n").trim() else "" }
            listOf(role, Vars.fillKeep(sp.second, fv, known + Vars.RAW).trim()).filter { it.isNotBlank() }.joinToString("\n\n")
        } else Vars.fillKeep(tplText, fv, known + Vars.RAW).trim()
        val desc = tplDesc.trim().trimEnd('。')
        secs += "【这类文章怎么写】\n类型：「$tplName」" + (if (desc.isNotEmpty()) "——$desc" else "") + "\n" + how
        if (xhs) secs += Prompts22.XHS
        secs += Prompts22.SELF_CHECK
        return secs.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}


/** 1.10：分享文件名（去掉非法字符、控制长度）；纯函数便于测试 */
object FileNames {
    fun sanitize(title: String, fallback: String = "文章"): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim().trim('.').take(60).ifBlank { fallback }
}


/**
 * 1.10 意图解析（容错）：
 * - 键名宽松：想写什么/我想写什么/主题/topic…，观点/我的观点/看法…，疑问/疑惑/我的疑惑/问题…；去掉空格、标点、花括号再比；
 * - 支持嵌套（{"意图":{"想写什么":…}}）、数组、以及「想写什么：…」这种纯文本行；
 * - 旧版清洗提示词把三项融合成一段（cleaned / 意图 字段）时，从那段话和原文里按句式抽取；
 * - 想写什么为空时：原文里有「我想写…」就用它，再不行用原文第一句。
 */
object IntentParse {
    private val alias = mapOf(
        "想写什么" to listOf("想写什么", "我想写什么", "写什么", "想写", "要写成", "写作主题", "文章主题", "主题", "写作意图", "要写什么", "topic", "subject", "whattowrite", "want", "wanttowrite", "goal", "article"),
        "观点" to listOf("观点", "我的观点", "我的看法", "看法", "立场", "主张", "想表达", "我想表达", "opinion", "viewpoint", "view", "stance", "point"),
        "疑问" to listOf("疑问", "疑惑", "我的疑惑", "我的疑问", "读者的疑问", "问题", "question", "questions", "doubt"))
    private val junk = setOf("", "null", "无", "没有", "未提及", "未提到", "none", "n/a", "……", "...", "-", "原文未明确表达")
    private fun norm(k: String) = k.lowercase().replace(Regex("[\\s{}\"'“”「」【】：:（）()_\\-]"), "")
    fun canon(k: String): String? { val n = norm(k); return alias.entries.firstOrNull { (_, l) -> l.any { norm(it) == n } }?.key }
    private fun clean(v: String): String { val t = v.trim().trim('"', '“', '”'); return if (junk.contains(t.lowercase()) || t.startsWith("原文未明确")) "" else t }

    private fun walk(o: Any?, out: MutableMap<String, String>, loose: MutableList<String>, depth: Int = 0) {
        if (depth > 4) return
        when (o) {
            is org.json.JSONObject -> o.keys().forEach { k ->
                val v = o.opt(k); val c = canon(k)
                when {
                    v is org.json.JSONObject -> walk(v, out, loose, depth + 1)
                    c != null -> { val s = if (v is org.json.JSONArray) (0 until v.length()).joinToString("；") { v.optString(it) } else if (v == null || v == org.json.JSONObject.NULL) "" else v.toString()
                        val cv = clean(s); if (cv.isNotEmpty() && out[c].isNullOrEmpty()) out[c] = cv }
                    v is String && k != "recommend" -> loose += v
                }
            }
            is org.json.JSONArray -> for (i in 0 until o.length()) walk(o.opt(i), out, loose, depth + 1)
        }
    }

    /** 从一段自然语言里按句式抽：我想写… / 我想表达… / …吗？ */
    fun fromText(t: String): Map<String, String> {
        val r = LinkedHashMap<String, String>()
        Regex("(?:我|就是)?(?:想|要|打算|准备)写(一篇|一个|篇|点|一部)?([^，。,.!！？?；;\\n]{2,40})").find(t)?.let { m -> r["想写什么"] = ("写" + m.groupValues[1].ifEmpty { "一篇" } + m.groupValues[2]).trim() }
        Regex("(?:我)?(?:想要?表达的?(?:是|观点是)?|我的观点是|我觉得|我认为)[：:，,]?([^。！!\\n]{2,80})").find(t)?.let { m -> r["观点"] = m.groupValues[1].trim().trimEnd('，', ',') }
        Regex("([^。！!？?\\n]{2,60}[？?])").findAll(t).map { it.groupValues[1].trim() }.firstOrNull { it.contains(Regex("为什么|怎么|如何|吗|呢|是不是|能不能|该不该")) }?.let { r["疑问"] = it }
        Regex("(?m)^\\s*[-*]?\\s*([^：:\\n]{1,8})[：:]\\s*(.+)$").findAll(t).forEach { m -> canon(m.groupValues[1])?.let { k -> clean(m.groupValues[2]).takeIf { it.isNotEmpty() }?.let { r[k] = it } } }
        return r
    }

    fun parse(reply: String, raw: String): CleanResult {
        val j = CleanParse.lenient(reply)
        val out = LinkedHashMap<String, String>(); val loose = mutableListOf<String>()
        if (j != null) walk(j, out, loose)
        val recommend = j?.optString("recommend").orEmpty().ifBlank { j?.optString("模板").orEmpty() }.trim().trim('「', '」', '"', '《', '》')
        // 回复里没有分开的三项：从融合段落 / 纯文本回复 / 原文里抽
        listOf(loose.joinToString("\n"), if (j == null) reply else "", raw).forEach { src -> fromText(src).forEach { (k, v) -> if (out[k].isNullOrEmpty()) out[k] = v } }
        if (j == null && out.isEmpty() && recommend.isEmpty()) throw CleanParse.BadFormat(reply.take(300))
        if (out["想写什么"].isNullOrEmpty()) raw.trim().split(Regex("[。！!？?\\n]")).firstOrNull { it.isNotBlank() }?.trim()?.take(40)?.let { out["想写什么"] = it }
        val values = LinkedHashMap<String, String>(); listOf("想写什么", "观点", "疑问").forEach { k -> out[k]?.takeIf { it.isNotBlank() }?.let { values[k] = it } }
        return CleanResult("", values, recommend)
    }

    /** 无论提示词怎么改，都在末尾强调输出格式（三项分开） */
    const val FORMAT = "\n\n【输出格式，务必遵守】只输出一个 JSON 对象，三项分开写，不要合并成一段：{\"想写什么\": \"一句话\", \"观点\": \"一句话，没有就省略\", \"疑问\": \"一句问句，没有就省略\", \"recommend\": \"模板名称\"}"
}
