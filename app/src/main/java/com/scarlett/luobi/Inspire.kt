package com.scarlett.luobi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/** 1.10「灵感」：每天抓公开热榜 → 一次 DeepSeek 调用按她的风格挑 10 个选题。没有服务器，结果按天缓存。 */
data class Topic(val title: String, val source: String, val angle: String = "", val view: String = "", val doubt: String = "", val heat: String = "")
data class HotItem(val title: String, val source: String, val extra: String = "", val heat: String = "")

/**
 * 热榜来源（2026-10-21 在盒子上实测，均无需登录）：
 * - 微博：weibo.com/ajax/side/hotSearch，必须带 Referer: https://weibo.com/ 与 X-Requested-With，否则 403
 * - 知乎：api.zhihu.com/topstory/hot-lists/total（www 域名的 v3 接口要登录，401）
 * - 抖音：www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/
 * - 百度：top.baidu.com/api/board?platform=wise&tab=realtime（2.2 起替换 B站；B站 wbi/search/square 要签名，实测一直 403）
 * - 小红书：没有可匿名访问的热榜接口（web 接口要签名 + 登录），不支持
 */
object HotSources {
    data class Src(val id: String, val name: String, val url: String, val headers: Map<String, String>, val parse: (String) -> List<HotItem>)
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun weibo(s: String): List<HotItem> = JSONObject(s).optJSONObject("data")?.optJSONArray("realtime").objs()
        .filter { it.optInt("is_ad", 0) == 0 && it.optString("word").isNotBlank() }.map { HotItem(it.optString("word"), "微博", it.optString("label_name"), heatOf(it.optLong("num"))) }
    fun zhihu(s: String): List<HotItem> = JSONObject(s).optJSONArray("data").objs().mapNotNull { o -> o.optJSONObject("target")?.let { o to it } }
        .filter { it.second.optString("title").isNotBlank() }.map { (o, t) -> HotItem(t.optString("title"), "知乎", t.optString("excerpt").take(60), o.optString("detail_text").replace(" ", "").removeSuffix("热度")) }
    fun douyin(s: String): List<HotItem> = JSONObject(s).optJSONArray("word_list").objs().filter { it.optString("word").isNotBlank() }.map { HotItem(it.optString("word"), "抖音", heat = heatOf(it.optLong("hot_value"))) }
    fun baidu(s: String): List<HotItem> = JSONObject(s).optJSONObject("data")?.optJSONArray("cards").objs().firstOrNull()?.optJSONArray("content").objs()
        .flatMap { it.optJSONArray("content").objs() }.filter { !it.optBoolean("isTop") && it.optString("word").isNotBlank() }.map { HotItem(it.optString("word"), "百度", it.optString("desc").take(60)) }
    /** 1243850 → 124万；0 → "" */
    fun heatOf(n: Long): String = when { n <= 0 -> ""; n >= 100_000_000 -> "%.1f亿".format(n / 1e8).replace(".0亿", "亿"); n >= 10_000 -> "${n / 10_000}万"; else -> n.toString() }

    val all = listOf(
        Src("weibo", "微博热搜", "https://weibo.com/ajax/side/hotSearch", mapOf("Referer" to "https://weibo.com/", "X-Requested-With" to "XMLHttpRequest"), ::weibo),
        Src("zhihu", "知乎热榜", "https://api.zhihu.com/topstory/hot-lists/total?limit=50", emptyMap(), ::zhihu),
        Src("douyin", "抖音热榜", "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/", emptyMap(), ::douyin),
        Src("baidu", "百度热搜", "https://top.baidu.com/api/board?platform=wise&tab=realtime", emptyMap(), ::baidu),
    )

    private fun get(src: Src): String {
        val c = URL(src.url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS; c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
        src.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try { if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
    /** 2.2：每个来源最多等 4 秒，超时就跳过 */
    const val TIMEOUT_MS = 4000
    /** 测试替身：src.id → 原始响应（抛异常 = 失败） */
    @Volatile var fake: ((Src) -> String)? = null
    /** 2.2：逐个来源回调，哪个先到先显示（失败给 null） */
    suspend fun stream(enabled: Set<String>, perSource: Int = 20, onSource: suspend (Src, List<HotItem>?) -> Unit) = coroutineScope {
        all.filter { it.id in enabled }.map { s -> async(Dispatchers.IO) {
            val r = runCatching { kotlinx.coroutines.withTimeout(TIMEOUT_MS + 500L) { kotlinx.coroutines.runInterruptible { s.parse(fake?.invoke(s) ?: get(s)).take(perSource) } } }.getOrNull()?.takeIf { it.isNotEmpty() }
            onSource(s, r)
        } }.awaitAll(); Unit
    }
    /** 并行抓取；任何一个来源失败就跳过，返回 [条目, 失败的来源名] */
    suspend fun fetch(enabled: Set<String>, perSource: Int = 20): Pair<List<HotItem>, List<String>> = withContext(Dispatchers.IO) {
        coroutineScope {
            all.filter { it.id in enabled }.map { s -> async { s to runCatching { s.parse(fake?.invoke(s) ?: get(s)).take(perSource) } } }.awaitAll()
        }.let { rs -> rs.flatMap { it.second.getOrDefault(emptyList()) } to rs.filter { it.second.isFailure || it.second.getOrNull().isNullOrEmpty() }.map { it.first.name } }
    }
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

object Inspire {
    const val DEFAULT_PROMPT = """你是一名资深内容编辑，熟悉我的写作风格和关注领域。

下面是今天各平台的热榜（来源｜标题｜补充）：
{热榜}

我最近写过的文章（标题与开头）：
{我的文章}

我常用的写作模板（名称：说明）：
{我的模板}

请从热榜里挑出最适合我来写的 10 个选题，要求：
1. 优先选和我写过的领域、常用模板相契合，并且有讨论空间、能写出观点的话题；
2. 避开纯娱乐八卦、灾难伤亡、敏感时政和无法核实的传闻；
3. 同一件事只保留一条，尽量覆盖不同来源；
4. 每个选题给出：title（选题标题，可在热榜原题基础上改写得更像一篇文章的题目，不超过 24 字）、source（来源平台：微博/知乎/抖音/百度）、angle（可以写的角度，一句话）、view（一个可能的观点，一句话，要有立场）、doubt（读者或作者可能有的一个疑惑，一句话，可留空）。

只输出一个 JSON 对象，不要解释：
{"topics": [{"title": "……", "source": "……", "angle": "……", "view": "……", "doubt": "……"}]}
""" + Prompts22.NO_FAB_LINE

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    data class Result(val topics: List<Topic>, val updatedAt: Long, val failed: List<String>, val note: String = "", val ai: Boolean = false)

    fun cached(ctx: Context): Result? {
        val p = ctx.getSharedPreferences("inspire", Context.MODE_PRIVATE)
        return runCatching { decode(p.getString("data", "") ?: "") }.getOrNull()
    }
    fun store(ctx: Context, r: Result) = ctx.getSharedPreferences("inspire", Context.MODE_PRIVATE).edit().putString("date", today()).putString("data", encode(r)).apply()

    fun encode(r: Result): String = JSONObject().put("at", r.updatedAt).put("note", r.note).put("failed", JSONArray(r.failed))
        .put("topics", JSONArray(r.topics.map { JSONObject().put("title", it.title).put("source", it.source).put("angle", it.angle).put("view", it.view).put("doubt", it.doubt).put("heat", it.heat) })).put("ai", r.ai).toString()
    fun decode(s: String): Result { val j = JSONObject(s); val t = j.getJSONArray("topics")
        return Result((0 until t.length()).map { t.getJSONObject(it).let { o -> Topic(o.optString("title"), o.optString("source"), o.optString("angle"), o.optString("view"), o.optString("doubt"), o.optString("heat")) } },
            j.optLong("at"), j.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), j.optString("note"), j.optBoolean("ai")) }

    /** 解析模型回复（容错：```json 围栏、topics/选题/list 键、直接数组） */
    fun parse(reply: String): List<Topic> {
        val s = reply.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        val arr: JSONArray? = CleanParse.lenient(s)?.let { j -> listOf("topics", "选题", "list", "items", "data").firstNotNullOfOrNull { j.optJSONArray(it) } }
            ?: runCatching { JSONArray(s.substring(s.indexOf('['), s.lastIndexOf(']') + 1)) }.getOrNull()
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            fun g(vararg k: String) = k.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: ""
            Topic(g("title", "标题", "选题"), g("source", "来源", "平台"), g("angle", "可以写", "角度"), g("view", "观点", "opinion"), g("doubt", "疑惑", "疑问"))
        }.filter { it.title.isNotBlank() }.distinctBy { it.title }.take(10)
    }

    fun buildPrompt(tpl: String, hot: List<HotItem>, notes: List<Note>, templates: List<Template>): String = tpl
        .replace("{热榜}", hot.joinToString("\n") { "${it.source}｜${it.title}" + if (it.extra.isNotBlank()) "｜${it.extra}" else "" })
        .replace("{我的文章}", notes.take(12).joinToString("\n") { "- ${it.title}：${Md.snippet(it.body).take(40)}" }.ifBlank { "（还没有文章）" })
        .replace("{我的模板}", templates.joinToString("\n") { "- ${it.name}：${it.desc}" })

    /** 「x 分钟前更新」 */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String { val m = (now - at) / 60_000
        return when { m < 1 -> "刚刚更新"; m < 60 -> "$m 分钟前更新"; m < 24 * 60 -> "${m / 60} 小时前更新"; else -> "${m / 1440} 天前更新" } }
    /** 打开时缓存超过这个时间就后台刷新 */
    const val STALE_MS = 30 * 60_000L

    /** 2.2 快速刷新：不调 AI，原始热榜按来源到达顺序逐步出现（各来源轮流排，最多 30 条） */
    suspend fun refresh(ctx: Context, onPartial: (Result) -> Unit): Result {
        val s = Settings(ctx); val got = linkedMapOf<String, List<HotItem>>(); val failed = mutableListOf<String>()
        val lock = Any()
        fun snapshot() = synchronized(lock) { Result(interleave(got.values.flatten()).take(30).map { Topic(it.title, it.source, heat = it.heat) }, System.currentTimeMillis(), failed.toList()) }
        HotSources.stream(s.inspireSources) { src, items ->
            synchronized(lock) { if (items == null) failed += src.name else got[src.id] = items }
            if (items != null) onPartial(snapshot())
        }
        val r = snapshot()
        if (r.topics.isEmpty()) throw PublishError("热榜都没抓到" + (if (failed.isNotEmpty()) "（${failed.joinToString("、")}）" else "") + "，请检查网络后重试")
        store(ctx, r); return r
    }

    fun target(ctx: Context): Ai.Target? { val s = Settings(ctx)
        return runCatching { Ai.target(ctx, "p:deepseek") }.getOrNull()
            ?: s.defaultModel.ifBlank { s.configured().firstOrNull()?.ref ?: "" }.takeIf { it.isNotBlank() }?.let { runCatching { Ai.target(ctx, it) }.getOrNull() } }

    /** 2.2 点一条热点 → 小 JSON 展开成 角度/观点/疑惑（max_tokens 小、关思考） */
    fun expandPrompt(t: Topic) = """热点（${t.source}）：${t.title}
请把它变成一篇可以写的文章选题，只输出 JSON：{"angle": "可以写的角度，一句话，具体", "view": "一个有立场的观点，一句话", "doubt": "一个读者真正会有的疑惑，问句"}
每项不超过 40 字，用全角中文标点。只根据标题本身能确定的信息来写，不要补充热点里的细节（人名、数字、经过）。
""" + Prompts22.NO_FAB_LINE
    fun parseExpand(t: Topic, reply: String): Topic? {
        val j = CleanParse.lenient(reply) ?: return null
        fun g(vararg k: String) = Sanitize.punct(k.firstNotNullOfOrNull { j.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: "").trim()
        val r = t.copy(angle = g("angle", "角度", "可以写"), view = g("view", "观点"), doubt = g("doubt", "疑惑", "疑问"))
        return r.takeIf { it.angle.isNotBlank() || it.view.isNotBlank() }
    }
    /** 仅供 JVM 截图测试：替代联网展开 */
    @Volatile var expandHook: (suspend (Topic) -> Topic)? = null
    suspend fun expand(ctx: Context, t: Topic): Topic {
        expandHook?.let { return it(t) }
        val target = target(ctx) ?: throw ConfigError("填写 DeepSeek Key 后，点热点就能生成写作角度和观点")
        val reply = Ai.chatText(target, "你是资深内容编辑。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE, expandPrompt(t), true, 300, Ai.DsGen(false, 0.7))
        val r = parseExpand(t, reply) ?: throw CleanParse.BadFormat(reply.take(200))
        // 写回缓存，下次打开还在
        cached(ctx)?.let { c -> store(ctx, c.copy(topics = c.topics.map { if (it.title == t.title && it.source == t.source) r else it })) }
        return r
    }

    /** 「AI 帮我挑」：按她的文章和模板从当前热榜里挑 10 个（旧的一次性流程，后台跑） */
    suspend fun aiPick(ctx: Context): Result {
        val s = Settings(ctx)
        val (hot, failed) = HotSources.fetch(s.inspireSources)
        if (hot.isEmpty()) throw PublishError("热榜都没抓到" + if (failed.isNotEmpty()) "（${failed.joinToString("、")}）" else "" + "，请检查网络后下拉重试")
        val db = AppDb.get(ctx)
        val prompt = buildPrompt(s.inspirePrompt, hot, db.notes().recent(12), db.templates().list())
        val target = target(ctx)
        val r = if (target == null) {
            // 没有任何 Key：如实显示原始热榜（不编角度/观点），提示去填 Key
            Result(interleave(hot).take(10).map { Topic(it.title, it.source) }, System.currentTimeMillis(), failed, "填写 DeepSeek Key 后，会按你的文章挑选并给出角度和观点")
        } else {
            val topics = parse(Ai.chatText(target, "你是资深内容编辑。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE, prompt, true, 2000, Ai.DsGen(false, 0.7)))
            if (topics.isEmpty()) throw CleanParse.BadFormat("")
            Result(topics, System.currentTimeMillis(), failed, ai = true)
        }
        store(ctx, r); return r
    }
    /** 各来源轮流取，保证没有 AI 时也覆盖多个平台 */
    fun interleave(hot: List<HotItem>): List<HotItem> { val g = hot.groupBy { it.source }.values.map { it.toMutableList() }; val out = ArrayList<HotItem>()
        while (g.any { it.isNotEmpty() }) g.forEach { if (it.isNotEmpty()) out += it.removeAt(0) }; return out }

    /** 点选题 → 填进输入框的文字 */
    fun fillText(t: Topic) = buildString {
        // 2.0.2：只填 我想写 / 我的观点 / 我的疑惑；不再带「话题」行和来源平台
        val a = t.angle.trim(); val ti = t.title.trim()
        val want = when { a.isBlank() -> ti; ti.isBlank() || a.contains(ti) -> a; else -> "$ti，$a" }
        if (want.isNotBlank()) append("我想写：").append(want)
        if (t.view.isNotBlank()) { if (isNotEmpty()) append("\n"); append("我的观点：").append(t.view.trim()) }
        if (t.doubt.isNotBlank()) { if (isNotEmpty()) append("\n"); append("我的疑惑：").append(t.doubt.trim()) }
    }
}
