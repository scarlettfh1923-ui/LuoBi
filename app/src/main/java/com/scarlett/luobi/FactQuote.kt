package com.scarlett.luobi

import org.json.JSONObject
import java.net.URLEncoder

/**
 * 1.16：引语 / 观点出处的专门检索。
 * 问题不在「谁来判」，而在「找不到出处」：整句中文去搜几乎搜不到外文原话。
 * 做法：抽出「谁 + 说了什么 + 可能出自哪本书」，用短查询（人名 + 关键词、原文语言、书名）去搜；
 * 一个源找不到相关资料就自动换下一个（Kimi → DeepSeek → 中文维基 → 英文维基 → 维基语录），直到找到原文段落；
 * 全部失败才标「无法核实」，并写明试过哪些源。
 */
object FactQuotes {
    data class Item(val speaker: String, val claim: String, val book: String, val enNames: List<String>, val enBook: String, val enTerms: List<String>, val keys: List<String>)
    data class Trail(val item: Item, val tried: List<String>, val found: FactHit?)

    /** 常见被引用者 → 原文名、代表作、关键原话（只是检索提示，不当作证据） */
    private data class Known(val en: List<String>, val books: Map<String, String>, val terms: Map<String, String>)
    private val known = mapOf(
        "柯林伍德" to Known(listOf("R. G. Collingwood", "Collingwood"), mapOf("历史的观念" to "The Idea of History", "历史观念" to "The Idea of History", "自传" to "An Autobiography"),
            mapOf("思想史" to "all history is the history of thought", "重演" to "re-enactment", "再现" to "re-enactment")),
        "克罗齐" to Known(listOf("Benedetto Croce", "Croce"), mapOf("历史学的理论和实际" to "History: Its Theory and Practice"), mapOf("当代史" to "all history is contemporary history")),
        "卡尔" to Known(listOf("E. H. Carr"), mapOf("历史是什么" to "What Is History?", "什么是历史" to "What Is History?"), mapOf("对话" to "unending dialogue between the present and the past")),
        "黑格尔" to Known(listOf("Hegel"), mapOf("历史哲学" to "Lectures on the Philosophy of History"), mapOf("教训" to "what experience and history teach")),
        "尼采" to Known(listOf("Nietzsche"), mapOf("善恶的彼岸" to "Beyond Good and Evil"), mapOf("深渊" to "gaze long into an abyss", "怪物" to "battles with monsters")),
        "维特根斯坦" to Known(listOf("Wittgenstein"), mapOf("逻辑哲学论" to "Tractatus Logico-Philosophicus"), mapOf("沉默" to "whereof one cannot speak", "界限" to "limits of my language")),
        "加缪" to Known(listOf("Albert Camus", "Camus"), mapOf("西西弗神话" to "The Myth of Sisyphus"), mapOf("西西弗" to "one must imagine Sisyphus happy")),
        "苏珊·桑塔格" to Known(listOf("Susan Sontag"), mapOf("论摄影" to "On Photography"), emptyMap()),
        "波兹曼" to Known(listOf("Neil Postman"), mapOf("娱乐至死" to "Amusing Ourselves to Death"), mapOf("娱乐" to "amusing ourselves to death")),
        "麦克卢汉" to Known(listOf("Marshall McLuhan"), mapOf("理解媒介" to "Understanding Media"), mapOf("媒介即讯息" to "the medium is the message")),
    )

    /** 常被引用开头 / 结尾的名著：中文名 → (作者, 英文书名, 原文关键句) */
    private data class Book(val author: String, val enAuthor: String, val en: String, val lines: Map<String, String>)
    private val books = mapOf(
        "百年孤独" to Book("马尔克斯", "Gabriel García Márquez", "One Hundred Years of Solitude", mapOf("行刑队" to "Many years later, as he faced the firing squad", "冰块" to "discover ice", "多年以后" to "Many years later, as he faced the firing squad")),
        "了不起的盖茨比" to Book("菲茨杰拉德", "F. Scott Fitzgerald", "The Great Gatsby", mapOf("逆流" to "boats against the current", "绿光" to "the green light", "往前划" to "boats against the current")),
        "双城记" to Book("狄更斯", "Charles Dickens", "A Tale of Two Cities", mapOf("最好的时代" to "It was the best of times, it was the worst of times")),
        "安娜·卡列尼娜" to Book("托尔斯泰", "Leo Tolstoy", "Anna Karenina", mapOf("幸福的家庭" to "Happy families are all alike")),
        "傲慢与偏见" to Book("简·奥斯汀", "Jane Austen", "Pride and Prejudice", mapOf("单身汉" to "a single man in possession of a good fortune")),
        "局外人" to Book("加缪", "Albert Camus", "The Stranger", mapOf("妈妈死了" to "Mother died today")),
        "1984" to Book("奥威尔", "George Orwell", "Nineteen Eighty-Four", mapOf("十三下" to "the clocks were striking thirteen", "老大哥" to "Big Brother is watching you")),
        "小王子" to Book("圣埃克苏佩里", "Antoine de Saint-Exupéry", "The Little Prince", mapOf("眼睛是看不见" to "what is essential is invisible to the eye")),
        "红楼梦" to Book("曹雪芹", "", "", emptyMap()), "围城" to Book("钱锺书", "", "", emptyMap()), "活着" to Book("余华", "", "", emptyMap()),
    )
    /** 「《书》的开头 / 结尾 / 第一句 / 写道：“……”」这类书中原句 */
    private val bookLineRe = Regex("《([^》]{1,30})》[^“「。！？\\n]{0,16}?(?:开头|开篇|第一句|首句|结尾|结局|最后一句|末句|写道|写到|里写|中写|有一句|说)[^“「。！？\\n]{0,8}?[：:，,]?\\s*[“「]([^”」]{4,120})[”」]")

    private val verbs = "(?:曾经说过|曾说过|曾说|说过|说|写道|写到|指出|认为|所言|提出|有言|有一句话|讲过)"
    private val quoteRe = Regex("([\\p{IsHan}·]{2,8})(?:（([A-Za-z. ]{3,40})）)?[^。！？\\n“「]{0,16}?$verbs[：:，,]?\\s*[“「]([^”」]{4,90})[”」]")
    private val dashRe = Regex("[“「]([^”」]{4,90})[”」]\\s*[——-]{1,2}\\s*([\\p{IsHan}·]{2,8})")
    private val bookRe = Regex("《([^》]{1,30})》")

    fun items(title: String, body: String): List<Item> {
        val text = title + "\n" + body
        val raw = quoteRe.findAll(text).map { Triple(it.groupValues[1].trimSpeaker(), it.groupValues[3], it.range) to it.groupValues[2] }.toList() +
            dashRe.findAll(text).map { Triple(it.groupValues[2], it.groupValues[1], it.range) to "" }.toList()
        return raw.mapNotNull { (t, latin) ->
            val (sp0, claim, range) = t
            val win = text.substring((range.first - 40).coerceAtLeast(0), (range.last + 40).coerceAtMost(text.length))
            val head = text.substring((range.first - 20).coerceAtLeast(0), (range.last + 1).coerceAtMost(text.length)).substringBefore(claim)
            // 已知人名优先；否则去掉「历史学家 / 哲学家」等头衔，取头衔后面的名字
            val sp = known.keys.firstOrNull { head.contains(it) }
                ?: sp0.split(Regex("(?:历史学家|哲学家|思想家|作家|学者|教授|导演|诗人|社会学家|经济学家|心理学家)")).last().trimSpeaker()
            if (sp.length < 2) return@mapNotNull null
            val book = bookRe.find(win)?.groupValues?.get(1).orEmpty()
            val k = known.entries.firstOrNull { sp.contains(it.key) || it.key.contains(sp) }?.value
            val enNames = (listOfNotNull(latin.trim().takeIf { it.isNotBlank() }) + (k?.en ?: emptyList())).distinct()
            val enBook = k?.books?.entries?.firstOrNull { book.contains(it.key) || claim.contains(it.key) }?.value ?: k?.books?.values?.firstOrNull().orEmpty()
            val enTerms = k?.terms?.entries?.filter { claim.contains(it.key) }?.map { it.value }.orEmpty()
            Item(sp, claim, book, enNames, enBook, enTerms, keyTerms(claim))
        }.let { quoted ->
            // 书中原句：说话人 = 作者（已知时）或书名本身
            val lines = bookLineRe.findAll(text).mapNotNull { m ->
                val book = m.groupValues[1]; val claim = m.groupValues[2]
                if (quoted.any { it.claim == claim }) return@mapNotNull null
                val b = books[book]
                Item(b?.author?.takeIf { it.isNotBlank() } ?: book, claim, book, listOfNotNull(b?.enAuthor?.takeIf { it.isNotBlank() }), b?.en.orEmpty(),
                    b?.lines?.entries?.filter { claim.contains(it.key) }?.map { it.value }.orEmpty().distinct(), keyTerms(claim))
            }.toList()
            (lines + quoted)
        }.distinctBy { it.speaker + it.claim }.take(5)
    }
    private fun String.trimSpeaker() = replace(Regex("^(?:正如|就像|如|哲学家|历史学家|作家|学者|英国|法国|德国|美国|意大利)+"), "")

    /** 关键词：去掉虚词后的 2–4 字片段，取最「实」的几个 */
    fun keyTerms(claim: String): List<String> {
        val stop = setOf("一切", "都是", "就是", "我们", "他们", "因为", "所以", "不是", "没有", "一个", "这个", "那个", "自己", "可以", "什么")
        val chunks = claim.split(Regex("[，,。；;、\\s的了是在和与都也就而]+")).map { it.trim() }.filter { it.length in 2..6 && it !in stop }
        return chunks.sortedByDescending { it.length }.take(3)
    }

    /** 短查询：人名 + 关键词 / 人名 + 书名 / 原文语言 / 书名；绝不整句 */
    fun queries(it: Item): Pair<List<String>, List<String>> {
        val zh = FactEvidence.dedupe(listOfNotNull(
            it.keys.take(2).takeIf { k -> k.isNotEmpty() }?.let { k -> (listOf(it.speaker) + k).joinToString(" ") },
            it.book.takeIf { b -> b.isNotBlank() }?.let { b -> (listOf("《$b》") + it.keys.take(2)).joinToString(" ") },
            it.book.takeIf { b -> b.isNotBlank() }?.let { b -> if (it.speaker == b) "《$b》 原文" else "${it.speaker} 《$b》" },
            if (it.book.isBlank()) "${it.speaker} 名言" else null,
        ), 3)
        val name = it.enNames.firstOrNull { n -> n.contains(' ') } ?: it.enNames.firstOrNull()
        val surname = it.enNames.lastOrNull()
        val en = if (name == null) emptyList() else FactEvidence.dedupe(
            it.enTerms.map { t -> "$surname \"$t\"" } + listOfNotNull(it.enBook.takeIf { b -> b.isNotBlank() }?.let { b -> "$name $b" }, it.enBook.takeIf { b -> b.isNotBlank() }) + listOf(name), 4)
        return zh to en
    }

    /** 资料与这条引语相关：提到说话人（中/外文名）且命中关键词、原文短语或书名 */
    fun relevant(h: FactHit, it: Item): Boolean {
        val t = (h.title + " " + h.snippet + " " + h.text).lowercase()
        val who = t.contains(it.speaker) || (it.book.isNotBlank() && t.contains(it.book)) || (it.enBook.isNotBlank() && t.contains(it.enBook.lowercase())) || it.enNames.any { n -> t.contains(n.lowercase().substringAfterLast(' ')) }
        val what = it.keys.any { k -> t.contains(k) } || it.enTerms.any { e -> t.contains(e.lowercase().take(18)) } ||
            (it.speaker != it.book && it.book.isNotBlank() && t.contains(it.book)) || (it.enBook.isNotBlank() && t.contains(it.enBook.lowercase()) && it.enTerms.isEmpty())
        return who && what
    }

    /** 原文段落：从全文里截出含原话 / 关键词的那一段 */
    fun passage(text: String, it: Item, cap: Int = 600): String {
        val paras = text.split(Regex("\\n+|(?<=[.!?。！？])\\s+")).map { p -> p.trim() }.filter { p -> p.length >= 8 }
        fun score(p: String) = it.enTerms.count { e -> p.lowercase().contains(e.lowercase().take(18)) } * 5 + it.keys.count { k -> p.contains(k) } * 2 +
            (if (p.contains(it.speaker) || it.enNames.any { n -> p.contains(n.substringAfterLast(' ')) }) 1 else 0)
        return paras.maxByOrNull { p -> score(p) }?.takeIf { p -> score(p) > 0 }?.take(cap).orEmpty()
    }

    // ---------- 外文维基 / 维基语录 ----------
    private val wikiH = mapOf("User-Agent" to FactCheck.UA, "Accept" to "application/json")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun mediaWiki(host: String, q: String, via: String): List<FactHit> {
        val u = "https://$host/w/api.php?action=query&list=search&srsearch=${enc(q)}&srlimit=3&format=json&utf8=1"
        val r = FactNet.call("GET", u, wikiH, null); if (r.code !in 200..299) throw FactNet.Fail(r.code, "HTTP ${r.code}")
        val arr = JSONObject(r.body).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o -> val t = o.optString("title")
            FactHit(t, "https://$host/wiki/" + enc(t.replace(' ', '_')), o.optString("snippet").replace(Regex("<[^>]+>"), ""), via, via) }
    }
    fun mediaWikiExtract(host: String, title: String): String {
        val u = "https://$host/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles=${enc(title)}"
        val r = FactNet.call("GET", u, wikiH, null); if (r.code !in 200..299) return ""
        val pages = JSONObject(r.body).optJSONObject("query")?.optJSONObject("pages") ?: return ""
        return pages.keys().asSequence().mapNotNull { pages.optJSONObject(it)?.optString("extract") }.firstOrNull().orEmpty()
    }

    /** 当前引语是否学术类（元宝用 Industry=acad） */
    @Volatile var scholarly = false
    private val scholarWords = listOf("哲学", "历史学", "学者", "教授", "社会学", "思想", "理论", "史学", "经济学", "心理学")
    fun isScholarly(it: Item) = it.enBook.isNotBlank() || it.book.isNotBlank() || scholarWords.any { w -> it.claim.contains(w) || it.speaker.contains(w) }

    /** 一个检索源：名字 + 用中文还是外文查询 + 怎么搜 + 怎么取全文 */
    class Src(val label: String, val english: Boolean, val search: (String) -> List<FactHit>, val full: ((FactHit) -> String)? = null)

    /** 兜底链：先她选中的，再补上其它可用的（有 Key 的 Kimi / DeepSeek、免 Key 的维基系） */
    fun chain(sel: List<FactCheck.SearchProvider>, keyOf: (FactCheck.SearchProvider) -> String, ctx: android.content.Context? = null, ybAllow: () -> Boolean = { true }): List<Src> {
        val order = ((if (FactCheck.SearchProvider.YUANBAO in sel) listOf(FactCheck.SearchProvider.YUANBAO) else emptyList()) + sel + listOf(FactCheck.SearchProvider.KIMI, FactCheck.SearchProvider.DEEPSEEK, FactCheck.SearchProvider.WIKI, FactCheck.SearchProvider.BOCHA)).distinct()
        val out = mutableListOf<Src>()
        for (p in order) {
            val k = keyOf(p)
            when (p) {
                FactCheck.SearchProvider.KIMI -> if (k.isNotBlank()) out += Src("Kimi 搜索", false, { q -> FactSources.kimiSearch(k, q, includeContent = true) })
                FactCheck.SearchProvider.DEEPSEEK -> if (k.isNotBlank()) out += Src("DeepSeek 搜索", false, { q -> FactSources.deepseek(k, q) })
                FactCheck.SearchProvider.YUANBAO -> if (k.isNotBlank()) out += Src("元宝搜索", false, { q ->
                    if (!ybAllow()) emptyList() else Yuanbao.search(k, q, industry = if (scholarly) "acad" else "", ctx = ctx).map(Yuanbao::toHit) })
                FactCheck.SearchProvider.BOCHA -> if (k.isNotBlank()) out += Src("博查", false, { q -> FactSources.bocha(k, q) })
                FactCheck.SearchProvider.WIKI -> {
                    out += Src("中文维基", false, { q -> FactSources.wikiSearch(q) }, { h -> FactSources.wikiExtract(h.title) })
                }
            }
        }
        out += Src("英文维基", true, { q -> mediaWiki("en.wikipedia.org", q, "英文维基") }, { h -> mediaWikiExtract("en.wikipedia.org", h.title) })
        out += Src("维基语录", true, { q -> mediaWiki("en.wikiquote.org", q, "维基语录") }, { h -> mediaWikiExtract("en.wikiquote.org", h.title) })
        return out
    }

    /** 对每条引语跑兜底链，找到相关原文段落就停 */
    fun resolve(items: List<Item>, chain: List<Src>): List<Trail> = items.map { item ->
        val (zh, en) = queries(item)
        scholarly = isScholarly(item)
        val tried = mutableListOf<String>()
        var found: FactHit? = null
        for (s in chain) {
            val qs = if (s.english) en else zh + en.take(1)
            if (qs.isEmpty()) continue
            tried += s.label
            for (q in qs) {
                val res = runCatching { s.search(q) }
                if (res.isFailure) break  // FactNet 已做 429 退避；仍失败就换下一个源
                val hits = res.getOrThrow()
                val rel = hits.filter { relevant(it, item) }.ifEmpty {
                    // 搜索摘要不够，取第一条的全文再判断
                    hits.take(1).mapNotNull { h -> s.full?.let { f -> runCatching { f(h) }.getOrNull()?.let { h.copy(text = it) } } }.filter { relevant(it, item) }
                }
                val h = rel.firstOrNull() ?: continue
                val full = h.text.ifBlank { s.full?.let { f -> runCatching { f(h) }.getOrNull() }.orEmpty() }
                val pas = passage(full, item).ifBlank { passage(h.snippet, item).ifBlank { h.snippet } }
                found = h.copy(via = s.label, text = pas, snippet = pas.take(280)); break
            }
            if (found != null) break
        }
        Trail(item, tried, found)
    }

    /** 引语核对结果写回：无资料的标「无法核实」并列出试过的源 */
    fun annotate(issues: List<FactIssue>, trails: List<Trail>): List<FactIssue> {
        val out = issues.map { iss ->
            val t = trails.firstOrNull { tr -> tr.item.claim.take(6).let { iss.span.contains(it) || iss.claim.contains(it) } || iss.span.contains(tr.item.speaker) } ?: return@map iss
            if (t.found == null) iss.copy(verdict = "无法核实", correction = "", note = "没有找到出处。已尝试：" + t.tried.joinToString("、") + "。")
            else iss.copy(links = (listOf(FactLink(t.found.title, t.found.url, t.found.via)) + iss.links).distinctBy { it.url },
                quote = iss.quote.ifBlank { t.found.text.take(200) }, corroboration = iss.corroboration.coerceAtLeast(1))
        }
        // 模型漏掉的引语也要列出来
        val extra = trails.filter { tr -> out.none { iss -> iss.span.contains(tr.item.speaker) || iss.span.contains(tr.item.claim.take(6)) } }.mapIndexed { i, tr ->
            val span = tr.item.claim
            if (tr.found == null) FactIssue("q$i", span, "引语出处", "中", "${tr.item.speaker}：「${tr.item.claim}」", "", "", "没有找到出处。已尝试：" + tr.tried.joinToString("、") + "。", selected = false, verdict = "无法核实")
            else FactIssue("q$i", span, "引语出处", "低", "${tr.item.speaker}：「${tr.item.claim}」", "", tr.found.title, "找到出处（${tr.found.via}）。", selected = false,
                verdict = "已印证", quote = tr.found.text.take(200), links = listOf(FactLink(tr.found.title, tr.found.url, tr.found.via)), corroboration = 1)
        }
        return out + extra
    }

    /** 给模型的证据：每条引语找到的原文段落 */
    fun evidence(trails: List<Trail>): List<FactHit> = trails.mapNotNull { it.found }
}
