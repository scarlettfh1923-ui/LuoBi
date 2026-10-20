package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 意图变量机制（需求说明 3.5 / 3.7）：纯逻辑，便于单元测试。
 * - 变量写成 {名字}；名字来自当前清洗模板的输出字段，另有系统变量 {原文}。
 * - 生成时把值原样替换进去；某行引用的变量全部为空时，这一行整行去掉。
 */
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
     * 按行替换：
     * - 一行里只要有一个被引用的变量为空（或不存在），整行删除；
     * - 否则把所有变量原样替换（不转义、不裁剪值本身）。
     * 变量名只认汉字/字母/数字/下划线，所以 JSON 示例这类花括号原样保留；
     * 引用了不存在的变量（如 {读者}）等同于空，整行去掉（编辑器会提前警告）。
     */
    fun fill(prompt: String, values: Map<String, String>): String {
                val out = ArrayList<String>()
        for (line in prompt.replace("\r\n", "\n").split("\n")) {
            val used = re.findAll(line).map { it.groupValues[1].trim() }.toList()
            if (used.any { values[it].isNullOrBlank() }) continue
            out += re.replace(line) { m -> val k = m.groupValues[1].trim(); values[k].orEmpty() }
        }
        return out.joinToString("\n")
    }

    /** 预览高亮用：返回 [文本, 是否是替换进来的值] 片段 */
    fun segments(prompt: String, values: Map<String, String>): List<Pair<String, Boolean>> {
                val res = ArrayList<Pair<String, Boolean>>()
        val lines = prompt.replace("\r\n", "\n").split("\n").filter { line ->
            re.findAll(line).map { it.groupValues[1].trim() }.none { values[it].isNullOrBlank() }
        }
        lines.forEachIndexed { li, line ->
            var last = 0
            for (m in re.findAll(line)) {
                val k = m.groupValues[1].trim()
                if (m.range.first > last) res += line.substring(last, m.range.first) to false
                res += values[k].orEmpty() to true
                last = m.range.last + 1
            }
            if (last < line.length) res += line.substring(last) to false
            if (li < lines.lastIndex) res += "\n" to false
        }
        return res
    }

    /** 被删掉的空变量（提示用户「{观点} 为空，那一行已去掉」） */
    fun emptyUsed(prompt: String, values: Map<String, String>): List<String> =
        referenced(prompt).filter { values[it].isNullOrBlank() }

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
    fun markdown(title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String {
        val ps = paras(body); val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val sb = StringBuilder("# ").append(title).append("\n\n")
        ps.forEachIndexed { i, p -> val h = heading(p, ps.size); sb.append(if (h != null) "## $h" else p).append("\n\n")
            bp[i]?.forEach { pic -> sb.append("![").append(pic.caption.replace("]", "")).append("](").append(pic.url).append(")\n")
                val cap = listOf(pic.caption, pic.creditLine()).filter { it.isNotBlank() }.joinToString(" · "); if (cap.isNotBlank()) sb.append("*").append(cap).append("*\n"); sb.append("\n") } }
        if (tags.isNotEmpty()) sb.append(tags.joinToString(" ") { "#$it" }).append("\n")
        return sb.toString().trimEnd() + "\n"
    }
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun inline(s: String) = esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    /** 正文片段（用于富文本复制 / 发布接口） */
    fun htmlBody(body: String, tags: List<String>, accent: String = "#2F6BFF", pics: List<Pic> = emptyList()): String {
        val ps = paras(body); val bp = Pics.byPara(pics.filter { it.isWeb }, ps.size)
        val sb = StringBuilder()
        ps.forEachIndexed { i, p ->
            val h = heading(p, ps.size)
            if (h != null) sb.append("<h2 style=\"font-size:19px;margin:26px 0 10px;padding-left:10px;border-left:3px solid $accent;\">").append(inline(h)).append("</h2>\n")
            else sb.append("<p style=\"font-size:16px;line-height:1.8;margin:0 0 14px;color:#111827;\">").append(inline(p)).append("</p>\n")
            bp[i]?.forEach { pic -> sb.append(figure(pic)) }
        }
        if (tags.isNotEmpty()) sb.append("<p style=\"color:$accent;font-size:14px;\">").append(tags.joinToString(" ") { "#" + esc(it) }).append("</p>\n")
        return sb.toString()
    }
    fun figure(pic: Pic): String {
        val sb = StringBuilder("<figure style=\"margin:16px 0 18px;\"><img src=\"").append(esc(pic.url)).append("\" alt=\"").append(esc(pic.caption)).append("\" style=\"width:100%;border-radius:10px;display:block;\">")
        val cap = listOf(pic.caption, pic.creditLine()).filter { it.isNotBlank() }
        if (cap.isNotEmpty()) sb.append("<figcaption style=\"font-size:12px;color:#8A94A6;text-align:center;margin-top:6px;\">").append(esc(cap.joinToString(" · "))).append("</figcaption>")
        return sb.append("</figure>\n").toString()
    }
    fun htmlDoc(title: String, body: String, tags: List<String>, pics: List<Pic> = emptyList()): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>body{margin:0;background:#F5F7FA;font-family:-apple-system,"Noto Sans SC","Source Han Sans SC","PingFang SC",sans-serif;color:#111827}
article{max-width:680px;margin:0 auto;padding:40px 22px 60px;background:#fff}h1{font-size:26px;line-height:1.4;margin:0 0 18px}</style></head>
<body><article><h1>${esc(title)}</h1>
${htmlBody(body, tags, pics = pics)}</article></body></html>
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
