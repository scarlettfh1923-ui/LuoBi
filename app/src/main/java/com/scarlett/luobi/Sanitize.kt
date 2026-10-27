package com.scarlett.luobi

/**
 * 2.2 乱码修复：
 * - Salvage：JSON 坏了（没转义的英文引号、被截断、半个转义）也能按字段抠出 title / body / tags / category；
 * - Sanitize：去掉渲染器不认的结构（参考文献、角标、链接、网址、代码、HTML、斜体、多余加粗、重复标题、话题行、字数说明），
 *   并在中文语境里把英文标点换成全角（数字、时间、小数、英文专名不动）。
 * 纯函数，见 Sanitize22Test。
 */
object Salvage {
    private val KEYS = "title|body|tags|category"
    /** 读 "key": "…" 的值；终止引号 = 后面紧跟 , "下一个键": 或 } 结尾的那个；找不到（截断）就读到末尾 */
    fun field(s: String, key: String): String? {
        val m = Regex("\"$key\"\\s*[:：]\\s*\"").find(s) ?: return null
        val from = m.range.last + 1
        val end = Regex("\"\\s*,?\\s*\"(?:$KEYS)\"\\s*[:：]|\"(?:$KEYS)\"\\s*[:：]|\"\\s*}\\s*(?:```)?\\s*$").findAll(s, from).firstOrNull()
        val rawVal = if (end != null) s.substring(from, end.range.first) else s.substring(from).trimEnd().trimEnd('}', '`').trimEnd().removeSuffix("\"")
        return unescape(rawVal)
    }
    fun tags(s: String): List<String> {
        val m = Regex("\"tags\"\\s*[:：]\\s*\\[([^\\]]*)\\]?").find(s) ?: return emptyList()
        return Regex("\"([^\"]{1,30})\"").findAll(m.groupValues[1]).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }
    fun unescape(v: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append(' '); 'r' -> {}; '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'u' -> { val h = v.substring(i + 2, minOf(v.length, i + 6)); val cp = h.toIntOrNull(16)
                        if (h.length == 4 && cp != null) { sb.append(cp.toChar()); i += 6; continue } else { i += 2; continue } }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '\\') { i++ } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
    /** 看起来是一段 JSON（不该给用户看） */
    fun looksJson(t: String): Boolean { val x = t.trim().removePrefix("```json").removePrefix("```").trim(); return x.startsWith("{") && Regex("\"(?:$KEYS)\"\\s*:").containsMatchIn(x) }
}

object Sanitize {
    private const val HAN = "\\p{IsHan}"
    private val refHead = Regex("^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?[【\\[]?(参考文献|参考资料|资料来源|数据来源|引用来源|注释|脚注|延伸阅读|References?|Sources?)[】\\]]?(?:\\*\\*)?\\s*[：:]?\\s*$", RegexOption.IGNORE_CASE)
    private val metaLine = Regex("^\\s*[（(]?\\s*(?:全文完|完|本文(?:约|共)?\\s*\\d+\\s*字.*|(?:全文)?(?:约|共)\\s*\\d+\\s*字|字数(?:统计)?\\s*[：:].*|待核实.*|写作思路\\s*[：:].*|以上(?:内容)?(?:为|是).*(?:仅供参考|生成).*)\\s*[)）]?\\s*$")
    private val hashLine = Regex("^\\s*(?:话题|标签|tags?)?\\s*[：:]?\\s*(?:#[^#\\s]{1,20}#?\\s*){1,}$", RegexOption.IGNORE_CASE)

    fun title(t: String): String {
        var s = t.replace(Regex("<[^>]+>"), "").trim().trimStart('#').trim().replace("**", "").trim()
        s = s.removePrefix("标题：").removePrefix("标题:").trim()
        s = punct(s)
        return s.trim('"', ' ').take(60)
    }

    fun body(input: String, title: String = ""): String {
        var s = input.replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        s = s.replace(Regex("```[a-zA-Z]*\\n?([\\s\\S]*?)```"), "$1")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</?(?:p|div|span|strong|b|em|i|u|h\\d|ul|ol|li|a|sup|sub|font|blockquote)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE), "")
        var lines = s.split("\n").toMutableList()
        // 尾部参考文献段：从这一行起全部截掉（只看后半篇，避免误伤正文里的「注释」二字）
        val cut = lines.indexOfFirst { refHead.matches(it) }
        if (cut >= 0 && cut >= lines.size / 3) lines = lines.subList(0, cut).toMutableList()
        lines.removeAll { Regex("^\\s*\\[\\^?\\d{1,3}]\\s*[:：].*").matches(it) }   // 脚注定义
        lines = lines.map { line(it) }.toMutableList()
        lines.removeAll { metaLine.matches(it) || (it.isNotBlank() && it.trim().matches(Regex("^[-*_]{3,}$")) && false) }
        // 去掉和标题重复的第一行
        val tNorm = norm(title)
        val fi = lines.indexOfFirst { it.isNotBlank() }
        if (fi >= 0 && tNorm.isNotEmpty() && norm(lines[fi]) == tNorm) lines.removeAt(fi)
        // 去掉结尾的话题标签行
        while (lines.isNotEmpty() && (lines.last().isBlank() || hashLine.matches(lines.last()))) lines.removeAt(lines.lastIndex)
        s = lines.joinToString("\n")
        s = limitBold(s, 3)
        s = s.replace(Regex("\n{3,}"), "\n\n").trim()
        return s
    }
    private fun norm(t: String) = t.replace(Regex("[#*\\s《》“”\"「」]"), "").trim()

    /** 一行：结构清理 + 中文标点 */
    fun line(l0: String): String {
        var l = l0
        l = l.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")                         // 图片
        l = l.replace(Regex("\\[([^\\]]+)]\\((?:https?://|www\\.)[^)]*\\)"), "$1")    // 链接 → 文字
        l = l.replace(Regex("\\[\\^?\\d{1,3}]|【\\d{1,3}】|\\[\\d{1,3}(?:[,，-]\\d{1,3})+]"), "")  // 角标
        l = l.replace(Regex("[（(]?\\s*(?:https?://|www\\.)[^\\s）)\\p{IsHan}]+\\s*[)）]?"), "")   // 网址
        l = l.replace(Regex("`([^`]*)`"), "$1")
        // 标题级别：# / ## → ##，### 及以下 → ###
        Regex("^\\s*(#{1,6})\\s*(.+)$").matchEntire(l)?.let { m -> if (!m.groupValues[2].startsWith("#")) l = (if (m.groupValues[1].length <= 2) "## " else "### ") + m.groupValues[2].trim() }
        // *斜体* / _斜体_ → 文字（不动 **加粗**）
        l = l.replace(Regex("(?<![*\\w])\\*(?![*\\s])([^*\\n]+?)(?<![*\\s])\\*(?![*\\w])"), "$1")
        l = l.replace(Regex("(?<![_\\w])_(?![_\\s])([^_\\n]+?)(?<![_\\s])_(?![_\\w])"), "$1")
        return punct(l)
    }

    /** 中文语境里的标点全角化 */
    fun punct(l0: String): String {
        if (!Regex(HAN).containsMatchIn(l0)) return l0
        var l = l0
        // 英文引号："…" 里有汉字 → “…”
        l = l.replace(Regex("\"([^\"\\n]*?)\"")) { m -> if (Regex(HAN).containsMatchIn(m.groupValues[1])) "“" + m.groupValues[1] + "”" else m.value }
        l = l.replace(Regex("'([^'\\n]*?\\p{IsHan}[^'\\n]*?)'"), "‘$1’")
        // 括号：里面有汉字 → 全角
        l = l.replace(Regex("\\(([^()\\n]*?\\p{IsHan}[^()\\n]*?)\\)"), "（$1）")
        // 省略号
        l = l.replace(Regex("\\.{3,}|。{3,}|…+|⋯+"), "……")
        // 破折号（汉字之间）
        l = l.replace(Regex("(?<=[\\p{IsHan}”）])\\s*(?:-{2,}|—+|–+)\\s*(?=[\\p{IsHan}“（])"), "——")
        // , ; : ? ! 挨着汉字/全角符号 → 全角（数字之间的 , : 不动：1,000 / 10:30）
        val sb = StringBuilder(l)
        val map = mapOf(',' to '，', ';' to '；', ':' to '：', '?' to '？', '!' to '！')
        for (i in sb.indices) {
            val c = sb[i]; val f = map[c] ?: continue
            val prev = (i - 1 downTo 0).firstOrNull { sb[it] != ' ' }?.let { sb[it] }
            val next = (i + 1 until sb.length).firstOrNull { sb[it] != ' ' }?.let { sb[it] }
            val prevD = i > 0 && sb[i - 1].isDigit(); val nextD = i + 1 < sb.length && sb[i + 1].isDigit()
            if ((c == ',' || c == ':') && prevD && nextD) continue
            if (c == ':' && next == '/') continue
            fun cjk(ch: Char?) = ch != null && (Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN || ch in "，。！？；：、“”‘’（）《》……——")
            if (cjk(prev) || (cjk(next) && prev?.isLetterOrDigit() != true)) sb.setCharAt(i, f)
            else if (cjk(next) && c != ':' ) sb.setCharAt(i, f)
        }
        l = sb.toString()
        // 句点夹在汉字之间 → 句号
        l = l.replace(Regex("(?<=[\\p{IsHan}”）])\\.(?=[\\p{IsHan}“（])"), "。")
        // 全角标点前后的多余空格、汉字之间的空格
        l = l.replace(Regex("[ \\t]*([，。！？；：、”’）》])[ \\t]*"), "$1").replace(Regex("([“‘（《])[ \\t]+"), "$1")
        l = l.replace(Regex("(?<=\\p{IsHan})[ \\t]+(?=\\p{IsHan})"), "")
        // 重复标点
        l = l.replace(Regex("([，。、；：])\\1+"), "$1").replace(Regex("？{2,}"), "？").replace(Regex("！{2,}"), "！").replace(Regex("[，、]([。！？])"), "$1")
        return l
    }

    /** 加粗最多 n 处，多出来的去掉 ** 保留文字 */
    fun limitBold(s: String, n: Int): String {
        var count = 0
        return Regex("\\*\\*([^*\\n]+?)\\*\\*").replace(s) { m -> count++; if (count <= n) m.value else m.groupValues[1] }
    }

    /** 纯文本（摘要等）：去掉所有标记 + 标点全角化 */
    fun plain(s: String): String = punct(s.replace(Regex("[#*>`]"), "").replace(Regex("\\[\\^?\\d{1,3}]"), "").replace(Regex("https?://\\S+"), "").trim())
}
