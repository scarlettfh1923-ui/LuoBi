package com.scarlett.luobi

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 技能写作里「讲得清原因」的失败（没搜到资料等），带标题 + 说明，直接变成错误卡 */
class SkillError(val title: String, val detail: String, val toSettings: Boolean = false) : Exception("$title：$detail") {
    fun friendly() = Friendly(title, detail, "", toSettings)
}

/** 一条检索结果。n 从 1 开始，与提示词里的 [S#] 对应 */
data class SkillSource(val n: Int, val title: String, val url: String, val site: String, val date: String, val via: String, val text: String) {
    fun json(): JSONObject = JSONObject().put("n", n).put("t", title).put("u", url).put("s", site).put("d", date).put("v", via).put("x", text)
    companion object { fun from(o: JSONObject) = SkillSource(o.optInt("n"), o.optString("t"), o.optString("u"), o.optString("s"), o.optString("d"), o.optString("v"), o.optString("x")) }
}

/** 角度 + 提纲（确认页可改）。note = 用户补充要求 */
data class SkillPlan(val obj: String, val angle: String, val alt: String, val outline: List<String>, val gaps: String, val words: String,
                     val tags: List<String>, val category: String, val note: String = "") {
    fun json(): JSONObject = JSONObject().put("object", obj).put("angle", angle).put("alt", alt).put("outline", JSONArray(outline)).put("gaps", gaps)
        .put("words", words).put("tags", JSONArray(tags)).put("category", category).put("note", note)
    /** 给阅读页「写作提纲」看的 JSON（沿用 Ai.Outline 的键） */
    fun outlineJson(): String = JSONObject().put("core", angle).put("sections", JSONArray(outline)).toString()
    companion object {
        private fun list(o: JSONObject, k: String): List<String> = when (val v = o.opt(k)) {
            is JSONArray -> (0 until v.length()).map { SkillParse2.item(v.opt(it)) }.filter { it.isNotBlank() }
            is String -> v.split('\n', '；', ';').map { SkillParse2.item(it) }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        fun from(o: JSONObject) = SkillPlan(o.optString("object").trim(), o.optString("angle").trim(), o.optString("alt").trim(), list(o, "outline"),
            o.optString("gaps").trim(), o.optString("words").trim(), list(o, "tags").map { it.trimStart('#') }.take(5), Categories.validate(o.optString("category")), o.optString("note"))
        /** 模型输出 → 计划；角度或提纲缺失就当格式不对 */
        fun parse(text: String): SkillPlan {
            val j = CleanParse.lenient(text) ?: throw AiFormatError("没有读到角度和提纲", text.take(300))
            val p = from(j)
            if (p.angle.isBlank() || p.outline.size < 2) throw AiFormatError("角度或提纲不完整", text.take(300))
            return p
        }
    }
}
internal object SkillParse2 {
    /** 提纲里的一项：去掉序号、## 记号、首尾空白；对象形式取 title/point */
    fun item(v: Any?): String = when (v) {
        is JSONObject -> listOf(v.optString("title").ifBlank { v.optString("heading") }, v.optString("point").ifBlank { v.optString("summary") }).filter { it.isNotBlank() }.joinToString("：")
        else -> v?.toString().orEmpty()
    }.trim().trimStart('#', '-', '*', ' ').replace(Regex("^\\d+[.、)）]\\s*"), "").trim()
}

/**
 * 一次技能写作的会话。存在 SharedPreferences（"skillflow"），切底栏 / 旋转 / 进程被杀都不丢。
 * phase：plan（搜资料 + 出提纲中）/ confirm（等她确认）/ write（写正文中）/ done / fail（搜资料阶段失败）。
 */
data class SkillSession(
    val phase: String, val topic: String, val skillId: String, val skillName: String, val skillVersion: String, val model: String,
    val stage: Int = 2, val line: String = "", val yb: Int = 0, val ds: Int = 0, val notices: List<String> = emptyList(),
    val queries: List<String> = emptyList(), val sources: List<SkillSource> = emptyList(), val plan: SkillPlan? = null,
    val err: Friendly? = null, val noteId: Long = 0, val words: Int = 0, val title: String = "", val startedAt: Long = System.currentTimeMillis(),
) {
    val busy get() = phase == "plan" || phase == "write"
    fun json(): String = JSONObject().put("phase", phase).put("topic", topic).put("skillId", skillId).put("skillName", skillName).put("skillVersion", skillVersion)
        .put("model", model).put("stage", stage).put("line", line).put("yb", yb).put("ds", ds).put("notices", JSONArray(notices)).put("queries", JSONArray(queries))
        .put("sources", JSONArray().also { a -> sources.forEach { a.put(it.json()) } }).put("plan", plan?.json() ?: JSONObject.NULL)
        .put("err", err?.let { JSONObject().put("t", it.title).put("d", it.detail).put("r", it.raw.take(300)).put("s", it.settings) } ?: JSONObject.NULL)
        .put("noteId", noteId).put("words", words).put("title", title).put("startedAt", startedAt).toString()
    companion object {
        private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        fun parse(s: String): SkillSession? = try {
            val j = JSONObject(s)
            SkillSession(j.optString("phase"), j.optString("topic"), j.optString("skillId"), j.optString("skillName"), j.optString("skillVersion"), j.optString("model"),
                j.optInt("stage", 2), j.optString("line"), j.optInt("yb"), j.optInt("ds"), strs(j.optJSONArray("notices")), strs(j.optJSONArray("queries")),
                j.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SkillSource::from) } } ?: emptyList(),
                j.optJSONObject("plan")?.let(SkillPlan::from), j.optJSONObject("err")?.let { Friendly(it.optString("t"), it.optString("d"), it.optString("r"), it.optBoolean("s")) },
                j.optLong("noteId"), j.optInt("words"), j.optString("title"), j.optLong("startedAt", System.currentTimeMillis())).takeIf { it.phase.isNotBlank() }
        } catch (_: Exception) { null }
    }
}

/** 进程级会话仓库：StateFlow 给界面，SharedPreferences 防被杀 */
object SkillFlow {
    val state = MutableStateFlow<SkillSession?>(null)
    private fun p(c: Context) = c.getSharedPreferences("skillflow", Context.MODE_PRIVATE)
    fun load(c: Context) { state.value = p(c).getString("s", null)?.let { SkillSession.parse(it) } }
    fun set(c: Context, s: SkillSession?) { state.value = s; p(c).edit().apply { if (s == null) remove("s") else putString("s", s.json()) }.apply() }
    fun clear(c: Context) = set(c, null)
    @Synchronized fun update(c: Context, persist: Boolean = true, f: (SkillSession) -> SkillSession) {
        val cur = state.value ?: return; val n = f(cur)
        if (persist) set(c, n) else state.value = n
    }
    fun start(c: Context, topic: String, pack: SkillPack, model: String): SkillSession =
        SkillSession("plan", topic, pack.id, pack.displayName, pack.version, model).also { set(c, it) }
}

/** 文章的参考资料（GB/T 7714 编号列表）。存在本机，按文章 id；改写出的新版本取不到就回到第一版 */
object Refs {
    private fun p(c: Context) = c.getSharedPreferences("refs", Context.MODE_PRIVATE)
    fun save(c: Context, id: Long, text: String) { if (text.isNotBlank()) p(c).edit().putString(id.toString(), text).apply() }
    fun get(c: Context, id: Long): String? = p(c).getString(id.toString(), null)
    fun forNote(c: Context, id: Long, root: Long): String? = get(c, id) ?: if (root != id && root > 0) get(c, root) else null
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotBlank() }
}

/** 技能包的成稿：可选 YAML frontmatter + `# 标题` + 正文 + `## 参考资料`。拆成 标题 / 正文 / 参考资料 */
data class SkillArticle(val title: String, val body: String, val refs: String) {
    companion object {
        private val refHead = Regex("^[ \\t]*(?:#{1,4}[ \\t]*)?(?:\\*\\*)?[【\\[]?(参考资料|参考文献|References?)[】\\]]?(?:\\*\\*)?[ \\t]*[：:]?[ \\t]*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        private val numbered = Regex("^(?:[-*]\\s+)?(?:\\[(\\d{1,3})]|(\\d{1,3}))\\s*[.．、)）]?\\s*(\\S.*)$")

        fun parse(raw: String, fallbackTitle: String = ""): SkillArticle {
            var s = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
            s = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            // 整篇被包在 ```markdown 围栏里
            Regex("^```(?:markdown|md)?[ \\t]*\\n([\\s\\S]*?)\\n?```\\s*$", RegexOption.IGNORE_CASE).find(s)?.let { s = it.groupValues[1].trim() }
            val (fm, rest) = SkillParse.frontmatter(s)
            // 取最后一个「参考资料」标题（正文里偶然出现同名小标题时以文末为准）
            val cuts = refHead.findAll(rest).toList()
            val cut = cuts.lastOrNull()
            val bodyRaw = if (cut == null) rest else rest.substring(0, cut.range.first)
            val refRaw = if (cut == null) "" else rest.substring(cut.range.last + 1)
            val tm = Regex("^[ \\t]*#[ \\t]+(.+?)[ \\t]*$", RegexOption.MULTILINE).find(bodyRaw)
            val title = Sanitize.title(tm?.groupValues?.get(1) ?: fm["title"].orEmpty()).ifBlank { Sanitize.title(fallbackTitle) }
            val body = Sanitize.body(if (tm != null) bodyRaw.removeRange(tm.range) else bodyRaw, title)
            return SkillArticle(title, body, cleanRefs(refRaw))
        }

        /** 只留编号条目，统一成「n. 内容」；缩进的续行并入上一条；遇到新的标题 / 分隔线 / 空行后的非编号段落就停 */
        fun cleanRefs(raw: String): String {
            val out = ArrayList<String>(); var blankBefore = false
            for (l0 in raw.lines()) {
                val l = l0.trim().replace("**", "")
                if (l.isEmpty()) { blankBefore = true; continue }
                if (l.startsWith("#") || l.matches(Regex("^[-*_]{3,}$")) || l.startsWith("```")) { if (out.isNotEmpty()) break else continue }
                val m = numbered.matchEntire(l)
                if (m != null) { out += m.groupValues[3].trim(); blankBefore = false }
                else if (out.isNotEmpty() && !blankBefore && (l0.startsWith(" ") || l0.startsWith("\t"))) out[out.lastIndex] = out.last() + " " + l
                else if (out.isNotEmpty() && blankBefore) break
            }
            return out.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        }
    }
}

/** 成稿流里的进度：标题（首个 # 行）、正文字数（到「参考资料」为止）、最后一行 */
object SkillPeek {
    fun progress(acc: String): GenProgress {
        val title = Regex("^[ \\t]*#[ \\t]+(.+?)[ \\t]*$", RegexOption.MULTILINE).find(acc)?.groupValues?.get(1).orEmpty().replace("**", "").take(60)
        val cut = Regex("^[ \\t]*#{1,4}[ \\t]*(?:参考资料|参考文献)", RegexOption.MULTILINE).find(acc)?.range?.first ?: acc.length
        val body = acc.substring(0, cut)
        return GenProgress(2, StreamPeek.words(body), title, if (cut < acc.length) "整理参考资料…" else StreamPeek.lastLine(body), stage = 2, stages = 2)
    }
}

object SkillRun {
    const val YB_QUERIES = 6; const val DS_QUERIES = 6; const val YB_CAP = 18; const val DS_CAP = 8; const val SNIPPET = 600

    class Research(val sources: List<SkillSource>, val yb: Int, val ds: Int, val notices: List<String>)

    private fun norm(u: String) = u.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('#').trimEnd('/')
    private fun clip(s: String) = s.replace(Regex("\\s+"), " ").trim().take(SNIPPET)

    /**
     * 检索：元宝优先（≤6 条查询），DeepSeek 搜索补充（元宝结果够多时只补前 3 条查询；元宝不可用 / 结果少时用全部查询）。
     * 没填元宝 Key 或元宝出错 → 全部改用 DeepSeek，并在 notices 里说明；两个都没有就返回空来源。
     */
    suspend fun research(ctx: Context?, queries: List<String>, ybKey: String, dsKey: String, progress: (stage: Int, line: String, yb: Int, ds: Int) -> Unit = { _, _, _, _ -> }): Research = withContext(Dispatchers.IO) {
        val notices = ArrayList<String>()
        val seen = HashSet<String>()
        val yb = ArrayList<SkillSource>(); val ds = ArrayList<SkillSource>()
        var ybOk = ybKey.isNotBlank()
        if (!ybOk) notices += "没填元宝搜索 Key，这次改用 DeepSeek 搜索。填好后会优先用元宝（设定 → 模型与密钥）。"
        else {
            for (q in queries.take(YB_QUERIES)) {
                kotlin.coroutines.coroutineContext.ensureActive()
                progress(2, "元宝正在搜：$q", yb.size, ds.size)
                try {
                    for (p in Yuanbao.search(ybKey, q, ctx = ctx).take(6)) {
                        if (yb.size >= YB_CAP) break
                        val url = p.url.trim(); val text = clip(p.passage.ifBlank { p.content })
                        if ((url.isBlank() && text.isBlank()) || !seen.add(norm(url).ifBlank { p.title })) continue
                        yb += SkillSource(0, p.title.trim(), url, p.site.ifBlank { "网页" }, p.date.take(10), "元宝", text)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    ybOk = false
                    notices += "元宝搜索没能用：${(e.message ?: "未知错误").take(120)}。" + if (dsKey.isNotBlank()) "已改用 DeepSeek 搜索。" else "又没有 DeepSeek Key 可以顶上。"
                    break
                }
            }
            if (ctx != null && Yuanbao.Usage.nearLimit(ctx)) notices += "元宝搜索本月额度快用完了（${Yuanbao.Usage.label(ctx)}）。"
        }
        if (dsKey.isBlank()) { if (!ybOk) notices += "也没有 DeepSeek Key，没有可用的搜索。" }
        else {
            val dq = if (!ybOk || yb.size < 6) queries.take(DS_QUERIES) else queries.take(3)
            for (q in dq) {
                kotlin.coroutines.coroutineContext.ensureActive()
                progress(3, "DeepSeek 正在搜：$q", yb.size, ds.size)
                try {
                    for (h in FactSources.deepseek(dsKey, q)) {
                        if (ds.size >= DS_CAP) break
                        val url = h.url.trim(); val text = clip(h.snippet)
                        if ((url.isBlank() && text.isBlank()) || !seen.add(norm(url).ifBlank { h.title })) continue
                        ds += SkillSource(0, h.title.trim(), url, h.source.ifBlank { "网页" }, h.date.take(10), "DeepSeek", text)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notices += "DeepSeek 搜索没能用：${(e.message ?: "未知错误").take(120)}。"; break }
            }
        }
        val all = (yb + ds).mapIndexed { i, s -> s.copy(n = i + 1) }
        Research(all, yb.size, ds.size, notices)
    }

    /** 让模型想检索词；失败就用启发式。始终返回 ≤6 条、去重、非空 */
    suspend fun planQueries(t: Ai.Target, ds: Ai.DsGen?, pack: SkillPack, topic: String): List<String> {
        val fallback = fallbackQueries(topic)
        val got = try {
            val u = "技能包：${pack.displayName}\n技能说明：${pack.description.take(260)}\n用户输入：${topic.take(400)}\n\n" +
                "请为这次写作规划 5–6 条检索词，用于联网搜索资料：中文为主，每条不超过 28 字，彼此不重复；" +
                "覆盖：对象识别与基本信息、时间线与关键节点、原始材料或一手来源、争议与反向检索（批评、质疑）、背景与数据。" +
                "用户输入若是一两句话，先抽出里面的核心对象再写检索词。\n只输出 JSON：{\"queries\":[\"…\",\"…\"]}"
            val txt = Ai.chatText(t, "你是资料检索规划助手。只输出一个合法 JSON 对象。", u, true, if (t.pid == "deepseek") 800 else null, null)
            val a = CleanParse.lenient(txt)?.optJSONArray("queries")
            if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).trim() }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        return (got.filter { it.isNotBlank() }.map { it.take(60) } + fallback).distinct().take(YB_QUERIES)
    }
    fun fallbackQueries(topic: String): List<String> {
        val t = topic.replace(Regex("\\s+"), " ").trim().take(40)
        return listOf(t, "$t 经历 时间线", "$t 争议 批评", "$t 报道 采访", "$t 原始材料 档案").distinct()
    }

    fun sourcesBlock(sources: List<SkillSource>): String = sources.joinToString("\n\n") { s ->
        "[S${s.n}] ${s.title.ifBlank { "（无标题）" }} | ${s.site}${if (s.date.isNotBlank()) " | " + s.date else ""} | ${s.url.ifBlank { "（无链接）" }}\n${s.text}"
    }

    const val ENV_PLAN = "\n\n---\n【落笔运行环境 · 现在处于「人工确认门」之前】\n" +
        "- 检索已由 App 完成：用户消息里的 [S1]… 就是你能用的全部来源。不得编造这些资料之外的事实、链接或来源；资料不足的地方写进 gaps。\n" +
        "- 这一步只做：对象识别、核心问题与主角度、提纲、资料缺口。不要写正文。\n" +
        "- 只输出一个 JSON 对象，不要任何其他文字。"
    private val PLAN_FORMAT = "{\"object\":\"对象识别（一句话，含身份假设）\",\"angle\":\"核心问题与主角度（2–4 句）\",\"alt\":\"备选角度（可空）\"," +
        "\"outline\":[\"引子：切入点\",\"小标题：这一节的一句要点\"],\"gaps\":\"资料缺口与争议（可空）\",\"words\":\"预计篇幅，如 4000–6000 字\"," +
        "\"tags\":[\"4–5 个不带#的主题标签\"],\"category\":\"必须从下列 15 个里原样选 1 个：${Categories.LIST.joinToString("、")}\"}\n" +
        "outline 给 5–8 项，每项形如「小标题：一句要点」，第一项是引子。不要加序号。"

    const val ENV_WRITE = "\n\n---\n【落笔运行环境 · 人工确认门已通过】\n" +
        "- 用户已确认下面的角度和提纲（含用户补充要求），按它写完整文章。只用用户消息里 [S#] 资料能支持的事实；资料里没有的不写，或在正文里明确标为某人的说法 / 传闻。\n" +
        "- 只输出一份 Markdown：可选 YAML frontmatter（title / skill / skill_version）、`# 标题`、正文（用 ## 小标题分节；正文里不加数字角标、脚注、网址）、" +
        "最后是 `## 参考资料`：一个连续编号的 GB/T 7714 列表，只列正文实际用到的来源，网页必须带资料里给出的完整链接，不得编造作者、出版者或日期。\n" +
        "- 不输出摘要、检索过程、核查表、备选标题、版本说明，也不输出 JSON。"

    suspend fun plan(ctx: Context) {
        val s0 = SkillFlow.state.value ?: throw SkillError("没有进行中的技能写作", "请回到「创作」重新开始。")
        val pack = Skills.find(ctx, s0.skillId) ?: throw SkillError("技能包不见了", "「${s0.skillName}」已被删除，请重新选一个技能包。")
        val text = Skills.text(ctx, pack) ?: throw SkillError("读不到技能包", "「${pack.displayName}」的文件读取失败，请到「技能包」里删除后重新导入。")
        val set = Settings(ctx)
        val t = Ai.target(ctx, s0.model)
        val ybKey = set.wsaKey.trim(); val dsKey = set.key("deepseek").trim()
        if (ybKey.isBlank() && dsKey.isBlank()) throw SkillError("还没有可用的搜索", "技能包要先联网搜资料。请填写元宝搜索 Key，或 DeepSeek 的 Key（设定 → 模型与密钥）。", true)
        SkillFlow.update(ctx) { it.copy(phase = "plan", stage = 2, line = "正在规划检索词…", err = null, notices = emptyList(), sources = emptyList(), plan = null, yb = 0, ds = 0) }
        val queries = planQueries(t, null, pack, s0.topic)
        SkillFlow.update(ctx) { it.copy(queries = queries) }
        val r = research(ctx, queries, ybKey, dsKey) { st, line, y, d -> SkillFlow.update(ctx, persist = false) { it.copy(stage = st, line = line, yb = y, ds = d) } }
        kotlin.coroutines.coroutineContext.ensureActive()
        if (r.sources.isEmpty()) throw SkillError("没有搜到可用资料", (r.notices + "换个更具体的关键词再试，或检查搜索 Key 与网络。不会在没有资料时硬写。").joinToString(" "), toSettings = r.notices.any { it.contains("Key") })
        SkillFlow.update(ctx) { it.copy(stage = 4, line = "整理角度与提纲…", yb = r.yb, ds = r.ds, notices = r.notices, sources = r.sources) }
        val user = "用户输入（选题）：${s0.topic}\n\n【检索资料】共 ${r.sources.size} 条（元宝 ${r.yb} · DeepSeek ${r.ds}）\n${sourcesBlock(r.sources)}\n\n请输出确认页内容，JSON 格式：\n$PLAN_FORMAT"
        val out = Ai.chatText(t, text + ENV_PLAN, user, true, if (t.pid == "deepseek") 4096 else null, Ai.dsGen(ctx))
        val plan = SkillPlan.parse(out)
        SkillFlow.update(ctx) { it.copy(phase = "confirm", plan = plan, line = "", err = null) }
    }

    /** 写正文。成功返回 note id；失败抛出，由调用方把会话退回 confirm */
    suspend fun write(ctx: Context, onDelta: (String) -> Unit): Long {
        val s = SkillFlow.state.value ?: throw SkillError("没有进行中的技能写作", "请回到「创作」重新开始。")
        val plan = s.plan ?: throw SkillError("还没有确认提纲", "请先回到确认页。")
        val pack = Skills.find(ctx, s.skillId) ?: throw SkillError("技能包不见了", "「${s.skillName}」已被删除。")
        val text = Skills.text(ctx, pack) ?: throw SkillError("读不到技能包", "「${pack.displayName}」的文件读取失败。")
        val t = Ai.target(ctx, s.model)
        val set = Settings(ctx)
        val fin = arrayOf("")
        val mt = if (t.pid == "deepseek") 16384 else null
        val user = buildString {
            append("用户输入（选题）：").append(s.topic).append("\n\n【已确认的写作角度】\n")
            if (plan.obj.isNotBlank()) append("对象：").append(plan.obj).append('\n')
            append(plan.angle).append("\n\n【已确认的提纲】\n")
            plan.outline.forEachIndexed { i, o -> append(i + 1).append(". ").append(o).append('\n') }
            if (plan.gaps.isNotBlank()) append("\n【资料缺口与争议（正文里要克制交代）】\n").append(plan.gaps).append('\n')
            if (plan.note.isNotBlank()) append("\n【用户补充要求（优先满足）】\n").append(plan.note.trim()).append('\n')
            append("\n【篇幅】").append(if (plan.words.isNotBlank()) "预计 ${plan.words}；" else "").append("用户补充里写了篇幅就按用户的，否则按技能包的默认篇幅。\n")
            append("\n【检索资料】共 ${s.sources.size} 条\n").append(sourcesBlock(s.sources))
            append("\n\n现在写完整文章（Markdown，文末 ## 参考资料）。")
        }
        val raw = Ai.chatStream(t, text + ENV_WRITE, user, false, Ai.dsGen(ctx), mt, fin, onDelta)
        kotlin.coroutines.coroutineContext.ensureActive()
        val a = SkillArticle.parse(raw, s.topic.take(30))
        if (a.body.isBlank()) throw AiFormatError("缺少正文", raw.take(200))
        val db = AppDb.get(ctx)
        val n = Note(title = a.title.ifBlank { s.topic.take(30) }, body = a.body, tags = plan.tags.joinToString(" "), raw = s.topic,
            intent = JSONObject().put("想写什么", s.topic).put("技能包", pack.displayName).toString(), category = plan.category, categoryAi = plan.category,
            templateId = 0, templateName = pack.displayName, model = "${t.name} · ${t.model}", finalPrompt = "skill:${pack.id}@${pack.version}")
        val id = db.notes().insert(n); db.notes().update(n.copy(id = id, rootId = id))
        Refs.save(ctx, id, a.refs)
        Ai.Outline.save(ctx, id, plan.outlineJson())
        if (fin[0] == "length") Ai.Truncated.mark(ctx, id)
        SkillFlow.update(ctx) { it.copy(phase = "done", noteId = id, title = n.title, words = StreamPeek.words(a.body), line = "", err = null) }
        return id
    }

    /** 失败后的会话状态：搜资料失败 → fail（回到起点）；写正文失败 → 退回 confirm，保留已确认的提纲 */
    fun fail(ctx: Context, kind: String, f: Friendly) {
        SkillFlow.update(ctx) { if (kind == "skill-plan") it.copy(phase = "fail", err = f, line = "") else it.copy(phase = "confirm", err = f, line = "") }
    }
    fun friendly(e: Throwable): Friendly {
        if (e is SkillError) return e.friendly()
        val f = Errors.explain(e)
        // 通用的格式错误提示会让人去改「提示词模板」；技能包流程里没有模板，换成对症的话
        if (f.title.contains("格式")) return f.copy(detail = "模型回复了，但不是技能包流程能读懂的格式（${(generateSequence(e) { it.cause }.filterIsInstance<AiFormatError>().firstOrNull()?.what ?: "内容不完整")}）。多半是偶发的，请直接点「重试」；反复出现的话，换一个模型再试。")
        return if (e is ConfigError) f.copy(settings = true) else f
    }
}
